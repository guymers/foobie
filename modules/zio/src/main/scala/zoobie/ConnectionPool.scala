package zoobie

import zio.Exit
import zio.Promise
import zio.Ref
import zio.Scope
import zio.Semaphore
import zio.Trace
import zio.UIO
import zio.ZIO
import zio.metrics.Metric.GaugeSyntax
import zio.metrics.MetricLabel

import java.sql.Connection
import java.time.Duration
import java.util.concurrent.TimeUnit
import scala.collection.immutable.Queue

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
    val errors = config.errors
    for {
      _ <- ZIO.die(new IllegalArgumentException(errors.mkString(", "))).when(errors.nonEmpty)
      demand <- Ref.make(0)
      idle <- Ref.make(Option(Queue.empty[ConnectionWrapper]))
      permits <- Semaphore.make(config.size.toLong)
      pool = new Pool(create, config, demand, idle, permits)
      _ <- ZIO.addFinalizer(pool.shutdown)
    } yield pool
  }

  /**
   * The pool has one permit per connection. A request holds a permit from
   * taking a connection until that connection is back in `idle`, or has been
   * discarded. A request that finds no idle connection creates one itself, so
   * connections are only created for a request and a failure to create one is
   * returned to it. A request that gives up while its connection is being
   * created leaves its permit with the attempt, which puts the connection in
   * `idle` once it has been created.
   *
   * @param demand
   *   number of requests either holding a permit or waiting for one, a
   *   connection attempt whose request gave up counts until it finishes
   * @param idle
   *   connections ready to be taken, `None` once the pool is closed
   */
  private final class Pool(
    create: ZIO[Any, DatabaseError.Connection, Connection],
    config: ConnectionPoolConfig,
    demand: Ref[Int],
    idle: Ref[Option[Queue[ConnectionWrapper]]],
    permits: Semaphore,
  ) extends ConnectionPool {

    private val waiting = metricWaiting(config.name)
    private val inUse = metricInUse(config.name)
    private val created = metricCreated(config.name)
    private val invalidated = metricInvalidated(config.name)

    private val capacity = config.size.toLong + config.queueSize.toLong
    private val validationTimeoutSeconds = jdbcTimeout(config.validationTimeout, TimeUnit.SECONDS)
    private val resetTimeoutMillis = jdbcTimeout(config.validationTimeout, TimeUnit.MILLISECONDS)

    override def get(implicit trace: Trace) = ZIO.uninterruptibleMask { restore =>
      for {
        // checked before waiting, a closed pool can not hand out a connection
        _ <- ZIO.interrupt.whenZIO(isClosed)
        admitted <- demand.modify(d => if (d >= capacity) (false, d) else (true, d + 1))
        _ <- ZIO.fail(DatabaseError.Connection.Rejected(config.queueSize)).unless(admitted)
        lease <- checkout(restore)
        _ <- ZIO.addFinalizerExit(checkin(lease, _))
      } yield lease.proxy
    }

    // only waiting for a permit or for a new connection can be interrupted. the permit is held by
    // a private scope that `release` closes once the connection is back in `idle` or discarded,
    // so the next request sees it no matter how the finalizers of the caller's scope are run. the
    // scope is only closed by this fiber once it has stopped waiting, a waiter whose scope is
    // closed by another fiber is never woken, see https://github.com/zio/zio/issues/11407
    private def checkout(restore: ZIO.InterruptibilityRestorer)(implicit trace: Trace) = for {
      permit <- Scope.make
      _ <- ZIO.acquireReleaseWith(waiting.increment)(_ => waiting.decrement) { _ =>
        restore(permit.extend[Any](permits.withPermitScoped))
      }.onError(cause => releaseDemand *> permit.close(Exit.failCause(cause)))
      // the pool was closed while waiting
      _ <- (release(permit) *> ZIO.interrupt).whenZIO(isClosed)
      c <- take.flatMap {
        case Some(c) =>
          connectionTooOld(c, jitter = None).flatMap { tooOld =>
            // been idle and missed invalidation, replace it. the request does not wait for it to
            // close, which can take as long as a network time-out
            if (tooOld) discard(c).forkDaemon *> connect(permit, restore) else Exit.succeed(c)
          }
        case None => connect(permit, restore)
      }
      _ <- inUse.increment
    } yield new Lease(c, new ConnectionProxy(c.connection, resetTimeoutMillis), permit)

    /**
     * Creates a connection on its own fiber, so the request can stop waiting
     * for it. Releases the permit unless the connection is returned to the
     * request. A connection created after the request stopped waiting is put in
     * `idle`, and a failure is logged.
     */
    private def connect(
      permit: Scope.Closeable,
      restore: ZIO.InterruptibilityRestorer,
    )(implicit trace: Trace) = for {
      result <- Promise.make[DatabaseError.Connection, ConnectionWrapper]
      _ <- open.exit.flatMap { exit =>
        result.done(exit).flatMap { delivered =>
          if (delivered) ZIO.unit
          else exit.foldCauseZIO(ZIO.logWarningCause("Could not create connection", _), putIdle) *> release(permit)
        }
      }.forkDaemon
      c <- restore(result.await).onExit {
        case Exit.Success(_) => ZIO.unit
        case Exit.Failure(_) =>
          // whichever completes the promise first, the request giving up or the attempt
          // finishing, leaves the result to the other
          result.interrupt.flatMap { gaveUpFirst =>
            if (gaveUpFirst) ZIO.unit
            else result.await.foldZIO(_ => ZIO.unit, putIdle) *> release(permit)
          }
      }
    } yield c

    private def checkin(lease: Lease, exit: Exit[Any, Any])(implicit trace: Trace) = {
      val c = lease.connection
      val proxy = lease.proxy

      // only reset the connection state if it is going back into the pool, a
      // connection that was not reset, or was aborted, is not safe to reuse
      val reset = logFailure("reset connection")(ZIO.attemptBlocking(proxy.close())) *> ZIO.succeed(proxy.reusable)

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
                if (reusable) {
                  c.isValid(validationTimeoutSeconds).catchAllCause { cause =>
                    ZIO.logWarningCause("Could not validate connection", cause).as(false)
                  }
                } else Exit.succeed(false)
              }
            }
          }
      }

      reuse.flatMap { reusable =>
        if (reusable) putIdle(c) else ZIO.succeed(proxy.discard()) *> discard(c)
      }.ensuring(inUse.decrement *> release(lease.permit))
    }

    /**
     * Closes the idle connections without waiting for the connections in use,
     * they are closed when they are returned. A request waiting for a
     * connection is interrupted once it gets a permit.
     */
    def shutdown(implicit trace: Trace): UIO[Unit] =
      idle.getAndSet(None).flatMap(q => ZIO.foreachDiscard(q.getOrElse(Queue.empty))(close))

    private def isClosed(implicit trace: Trace) = idle.get.map(_.isEmpty)

    private def take(implicit trace: Trace) = idle.modify {
      case Some(q) =>
        q.dequeueOption.fold((Option.empty[ConnectionWrapper], Option(q))) { case (c, rest) => (Some(c), Some(rest)) }
      case None => (None, None)
    }

    /** Puts a connection in `idle`, or closes it if the pool is closed. */
    private def putIdle(c: ConnectionWrapper)(implicit trace: Trace) = idle.modify {
      case Some(q) => (true, Some(q.enqueue(c)))
      case None => (false, None)
    }.flatMap(added => close(c).unless(added).unit)

    private def releaseDemand(implicit trace: Trace) = demand.update(_ - 1)

    // demand is released first, a request admitted in between waits for a permit that is about to
    // be released. the other way around it would be rejected while a permit is free
    private def release(permit: Scope.Closeable)(implicit trace: Trace) =
      releaseDemand *> permit.close(Exit.unit)

    private def open(implicit trace: Trace) = for {
      connection <- create
      acquired <- zio.Clock.nanoTime
      _ <- created.increment
    } yield new ConnectionWrapper(connection, acquired)

    private def discard(c: ConnectionWrapper)(implicit trace: Trace) = invalidated.increment *> close(c)

    private def close(c: ConnectionWrapper)(implicit trace: Trace) = closeConnection(c.connection)

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

  /**
   * Closes a connection, a failure is logged as there is no request to return
   * it to.
   */
  private[zoobie] def closeConnection(connection: Connection)(implicit trace: Trace): UIO[Unit] =
    logFailure("close connection")(ZIO.attemptBlocking(connection.close()))

  private def logFailure(action: String)(effect: ZIO[Any, Throwable, Any])(implicit trace: Trace): UIO[Unit] =
    effect.foldCauseZIO(ZIO.logWarningCause(s"Could not $action", _), _ => ZIO.unit)

  private final class Lease(
    val connection: ConnectionWrapper,
    val proxy: ConnectionProxy,
    val permit: Scope.Closeable,
  )

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
