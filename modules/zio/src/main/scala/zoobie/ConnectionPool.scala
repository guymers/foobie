package zoobie

import zio.Exit
import zio.Ref
import zio.Scope
import zio.Trace
import zio.ZIO
import zio.ZLayer
import zio.ZPool
import zio.metrics.Metric.GaugeSyntax
import zio.metrics.MetricLabel

import java.sql.Connection
import java.time.Duration
import java.util.concurrent.TimeUnit

trait ConnectionPool {
  def get(implicit trace: Trace): ZIO[Scope, DatabaseError.Connection, Connection]
}
object ConnectionPool {

  private[zoobie] def metricWaiting(pool: String) =
    zio.metrics.Metric.gauge("zoobie_connections_waiting").tagged(MetricLabel("pool", pool))

  private[zoobie] def metricInUse(pool: String) =
    zio.metrics.Metric.gauge("zoobie_connections_in_use").tagged(MetricLabel("pool", pool))

  private[zoobie] def metricCreated(pool: String) =
    zio.metrics.Metric.counter("zoobie_connections_created").tagged(MetricLabel("pool", pool))

  private[zoobie] def metricInvalidated(pool: String) =
    zio.metrics.Metric.counter("zoobie_connections_invalidated").tagged(MetricLabel("pool", pool))

  def create(
    create: ZIO[Any, DatabaseError.Connection, Connection],
    config: ConnectionPoolConfig,
  )(implicit trace: Trace): ZIO[Scope, Nothing, ConnectionPool] = {

    val waiting = metricWaiting(config.name)
    val inUse = metricInUse(config.name)
    val created = metricCreated(config.name)
    val invalidated = metricInvalidated(config.name)

    val acquire_ = for {
      connection <- create
      acquired <- zio.Clock.nanoTime
      _ <- created.increment
    } yield new ConnectionWrapper(connection, acquired)

    val conn = ZIO.acquireRelease(acquire_)(_.close.ignoreLogged)

    val validationTimeoutSeconds = jdbcTimeout(config.validationTimeout, TimeUnit.SECONDS)
    val resetTimeoutMillis = jdbcTimeout(config.validationTimeout, TimeUnit.MILLISECONDS)

    def acquireRelease[E, A](scope: Scope)(acquire: => ZIO[Any, E, A], release: => ZIO[Any, Nothing, Any]) =
      ZIO.uninterruptible(acquire.tap(_ => scope.addFinalizerExit(_ => release)))

    for {
      numQueuedRef <- Ref.make[Int](0)
      pool <- ZPool.make(conn, config.size)
      invalidate = (c: ConnectionWrapper) => pool.invalidate(c) <* invalidated.increment
    } yield {
      new ConnectionPool {
        override def get(implicit trace: Trace) = {
          @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
          def go(i: Int): ZIO[Scope, DatabaseError.Connection, Connection] = {
            ZIO.die(new IllegalStateException(s"could not get connection, tried ${i.toString} times")).when(i > config.size * 2) *>
              getCheckingAge.flatMap {
                case None => go(i + 1)
                case Some(c) => Exit.succeed(c)
              }
          }
          go(1)
        }

        private def getCheckingAge(implicit trace: Trace) = for {
          tuple <- _get.withEarlyRelease
          (close, (wrapper, proxy)) = tuple
          tooOld <- connectionTooOld(wrapper, jitter = None)
          c <- if (tooOld) {
            close.as(None) // been idle and missed invalidation, close and let the finalizer invalidate
          } else Exit.succeed(Some(proxy))
        } yield c

        private def _get(implicit trace: Trace) = ZIO.scopeWith { outer =>
          for {
            inner <- outer.fork
            numQueued <- acquireRelease(inner)(numQueuedRef.updateAndGet(_ + 1), numQueuedRef.update(_ - 1))
            _ <- ZIO.fail(DatabaseError.Connection.Rejected(config.queueSize)).when(numQueued > config.queueSize)

            _ <- acquireRelease(inner)(waiting.increment, waiting.decrement)
            c <- pool.get.provide(ZLayer.succeed(outer))
            _ <- inner.close(Exit.unit)
            _ <- acquireRelease(outer)(inUse.increment, inUse.decrement)
            proxy = new ConnectionProxy(c.connection, resetTimeoutMillis)

            _ <- outer.addFinalizerExit { exit =>
              // only reset the connection state if it is going back into the pool, a
              // connection that was not reset, or was aborted, is not safe to reuse
              val reset = ZIO.attemptBlocking(proxy.close()).ignoreLogged *> ZIO.succeed(proxy.reusable)

              val reuse = exit match {
                case Exit.Success(_) =>
                  connectionTooOld(c, jitter = Some(0.9)).flatMap(tooOld => if (tooOld) Exit.succeed(false) else reset)
                case Exit.Failure(_) if config.alwaysInvalidateOnFailure => Exit.succeed(false)
                case Exit.Failure(_) =>
                  // validate after the reset so a clean connection is checked, the reset may not
                  // reach the server, e.g. there was no transaction to roll back
                  connectionTooOld(c, jitter = None).flatMap { tooOld =>
                    if (tooOld) {
                      Exit.succeed(false)
                    } else {
                      reset.flatMap { reusable =>
                        if (reusable) c.isValid(validationTimeoutSeconds).catchAll(_ => Exit.succeed(false)) else Exit.succeed(false)
                      }
                    }
                  }
              }

              reuse.flatMap { reusable =>
                (ZIO.succeed(proxy.discard()) *> invalidate(c)).unless(reusable)
              }
            }
          } yield (c, proxy)
        }

        // a lifetime too large to represent in nanoseconds never expires
        private val maxLifetimeNanos =
          try {
            config.maxConnectionLifetime.toNanos
          } catch {
            case _: ArithmeticException => Long.MaxValue
          }

        private def connectionTooOld(
          wrapper: ConnectionWrapper,
          jitter: Option[Double],
        )(implicit trace: Trace) = for {
          now <- zio.Clock.nanoTime
          age = now - wrapper.acquired
          maxLifetimeJitter <- ZIO.foreach(jitter)(zio.Random.nextDoubleBetween(_, 0.99)).map(_.getOrElse(1.0))
          maxLifetime = (maxLifetimeNanos * maxLifetimeJitter).toLong
        } yield {
          age > maxLifetime
        }
      }
    }
  }

  class ConnectionWrapper(val connection: Connection, val acquired: Long) {

    def close(implicit trace: Trace): ZIO[Any, Throwable, Unit] = {
      ZIO.attemptBlocking(connection.close())
    }

    def isValid(timeoutSeconds: Int)(implicit trace: Trace): ZIO[Any, Throwable, Boolean] = {
      ZIO.attemptBlocking(connection.isValid(timeoutSeconds))
    }
  }

  /**
   * A timeout as JDBC takes it, a whole number of the given unit where zero
   * means no timeout. Rounded up so a timeout is never turned into none, and
   * capped. A negative timeout is zero.
   */
  private[zoobie] def jdbcTimeout(timeout: Duration, unit: TimeUnit): Int = {
    if (timeout.isNegative) {
      0
    } else {
      val whole = unit.convert(timeout).min(Int.MaxValue.toLong)
      val partial = whole < Int.MaxValue && timeout.compareTo(Duration.of(whole, unit.toChronoUnit)) > 0
      (if (partial) whole + 1 else whole).toInt
    }
  }
}
