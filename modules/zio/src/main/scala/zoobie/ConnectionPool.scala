package zoobie

import zio.Exit
import zio.Ref
import zio.Scope
import zio.Trace
import zio.ZIO
import zio.ZLayer
import zio.ZPool
import zio.duration2DurationOps
import zio.durationLong
import zio.metrics.Metric.GaugeSyntax
import zio.metrics.MetricLabel

import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration

trait ConnectionPool {
  def get(implicit trace: Trace): ZIO[Scope, DatabaseError.Connection, Connection]
}
object ConnectionPool {

  def jdbc(
    config: ConnectionPoolConfig,
    driver: String,
    url: String,
    info: java.util.Properties,
  )(implicit trace: Trace): ZIO[Scope, Nothing, ConnectionPool] = for {
    _ <- ZIO.succeed(Class.forName(driver))
    createConnection = ZIO.attemptBlocking {
      DriverManager.getConnection(url, info)
    }.mapError(DatabaseError.Connection(_))
    pool <- ConnectionPool.create(createConnection, config)
  } yield pool

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
          go(1).map(new ConnectionProxy(_))
        }

        private def getCheckingAge(implicit trace: Trace) = for {
          tuple <- _get.withEarlyRelease
          (close, wrapper) = tuple
          tooOld <- connectionTooOld(wrapper, jitter = None)
          c <- if (tooOld) {
            close.as(None) // been idle and missed invalidation, close and let the finalizer invalidate
          } else Exit.succeed(Some(wrapper.connection))
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

            _ <- outer.addFinalizerExit {
              case Exit.Success(_) =>
                invalidate(c).whenZIO {
                  connectionTooOld(c, jitter = Some(0.9))
                }

              case Exit.Failure(_) =>
                invalidate(c).whenZIO {
                  for {
                    tooOld <- connectionTooOld(c, jitter = None)
                    valid <- c.isValid(config.validationTimeout).catchAll(_ => Exit.succeed(false))
                  } yield tooOld || !valid
                }
            }
          } yield c
        }

        private def connectionTooOld(
          wrapper: ConnectionWrapper,
          jitter: Option[Double],
        )(implicit trace: Trace) = for {
          now <- zio.Clock.nanoTime
          age = (now - wrapper.acquired).nanoseconds
          maxLifetimeJitter <- ZIO.foreach(jitter)(zio.Random.nextDoubleBetween(_, 0.99)).map(_.getOrElse(1.0))
          maxLifetime = (config.maxConnectionLifetime.toNanos * maxLifetimeJitter).toLong.nanoseconds
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

    def isValid(timeout: Duration)(implicit trace: Trace): ZIO[Any, Throwable, Boolean] = {
      ZIO.attemptBlocking(connection.isValid(millis(timeout)))
    }

    def setNetworkTimeout(timeout: Duration)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] = for {
      executor <- ZIO.blockingExecutor
      current = connection.getNetworkTimeout
      _ <- ZIO.acquireRelease({
        ZIO.succeed(connection.setNetworkTimeout(executor.asJava, millis(timeout)))
      })(_ => {
        ZIO.succeed(connection.setNetworkTimeout(executor.asJava, current))
      })
    } yield ()

    private def millis(timeout: Duration) = timeout.toMillis.min(Int.MaxValue).toInt.max(0)
  }
}
