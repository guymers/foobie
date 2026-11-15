package zoobie

import zio.Exit
import zio.Promise
import zio.Ref
import zio.Scope
import zio.Trace
import zio.UIO
import zio.ZIO
import zio.metrics.Metric.GaugeSyntax
import zio.metrics.MetricLabel

import java.sql.Connection
import java.time.Duration
import java.util.concurrent.TimeUnit
import scala.collection.immutable.TreeMap
import scala.util.control.NonFatal

trait ConnectionPool {
  def get(implicit trace: Trace): ZIO[Scope, DatabaseError.Connection, Connection]
}
object ConnectionPool {

  private[zoobie] def metricWaiting(pool: String) =
    zio.metrics.Metric.gauge("zoobie_connections_waiting").tagged(MetricLabel("pool", pool))

  private[zoobie] def metricInUse(pool: String) =
    zio.metrics.Metric.gauge("zoobie_connections_in_use").tagged(MetricLabel("pool", pool))

  // Counts connections being created, including those whose request stopped
  // waiting, which hold a slot until they finish.
  private[zoobie] def metricCreating(pool: String) =
    zio.metrics.Metric.gauge("zoobie_connections_creating").tagged(MetricLabel("pool", pool))

  private[zoobie] def metricCreated(pool: String) =
    zio.metrics.Metric.counter("zoobie_connections_created").tagged(MetricLabel("pool", pool))

  private[zoobie] def metricInvalidated(pool: String) =
    zio.metrics.Metric.counter("zoobie_connections_invalidated").tagged(MetricLabel("pool", pool))

  /**
   * Creates a pool that never interrupts `create`, so `create` must not hang:
   * an attempt holds a slot until it finishes and shutdown waits for it.
   *
   * By design the pool does not put a timeout on `create`. Bounding connection
   * creation is the job of `create` and the driver configuration it uses.
   * `create` runs in an uninterruptible region, so a connection it has opened
   * is never dropped by an interruption. A timeout inside it only takes effect
   * on the parts `create` itself marks as interruptible.
   */
  def create(
    create: ZIO[Any, DatabaseError.Connection, Connection],
    config: ConnectionPoolConfig,
  )(implicit trace: Trace): ZIO[Scope, Nothing, ConnectionPool] = for {
    _ <- ZIO.unit
    errors = config.errors
    _ <- ZIO.die(new IllegalArgumentException(errors.mkString(", "))).when(errors.nonEmpty)
    state <- Ref.make[PoolState](PoolState.empty)
    pool = new Pool(create, config, state)
    _ <- ZIO.addFinalizer(pool.shutdown)
    // Forked after the shutdown finalizer so the sweeper stops before shutdown
    // runs.
    _ <- pool.sweep.interruptible.forkScoped
  } yield pool

  /**
   * A bounded, on-demand connection pool.
   *
   * Each checkout and creation attempt holds a slot, at most `size` at once. A
   * request that cannot get a slot waits in a queue of at most `queueSize`, and
   * slots are handed to waiters in order. Idle connections hold no slot and are
   * reused before new ones are created, the most recently returned first. A
   * quiet pool then keeps using the same few connections, which are the least
   * likely to have been dropped while idle, and leaves the rest to expire.
   *
   * Checkout gets a slot, then takes an idle connection or starts a creation
   * attempt. A request can stop waiting without cancelling creation: the
   * attempt keeps its slot until it finishes. Unclaimed connections are queued
   * while the pool is open or closed during shutdown; unobserved failures are
   * logged.
   *
   * Expired and invalidated connections close on separate fibers that hold no
   * slot, so neither checkout nor check-in waits for them; a checkout that
   * finds an expired connection keeps its slot and tries again. Shutdown does
   * not wait for them by design: it only runs when the application exits.
   *
   * A sweeper fiber closes idle connections whose lifetime has ended every 30
   * seconds, as HikariCP's housekeeper does, so they do not stay open while the
   * pool is quiet. An idle connection can outlive its lifetime by up to that
   * long, and is never handed out once its lifetime has ended.
   *
   * `state` holds the idle stack, slot count and slot waiters, or once closed,
   * the number of slots shutdown still waits for.
   */
  private final class Pool(
    create: ZIO[Any, DatabaseError.Connection, Connection],
    config: ConnectionPoolConfig,
    state: Ref[PoolState],
  ) extends ConnectionPool {

    private val waiting = metricWaiting(config.name)
    private val inUse = metricInUse(config.name)
    private val creating = metricCreating(config.name)
    private val created = metricCreated(config.name)
    private val invalidated = metricInvalidated(config.name)

    private val validationTimeoutSeconds = jdbcTimeout(config.validationTimeout, TimeUnit.SECONDS)
    private val resetTimeoutMillis = jdbcTimeout(config.validationTimeout, TimeUnit.MILLISECONDS)

    private val maxLifetimeNanos = config.maxConnectionLifetime.toNanos

    /**
     * Marks the pool closed, interrupts requests waiting for a slot, and closes
     * idle connections. Then waits for check-ins and creation attempts to
     * finish. Connections returned after closure are closed instead of queued.
     * A request that already has a slot still gets a connection. New requests
     * fail with [[DatabaseError.Connection.Closed]].
     */
    def shutdown(implicit trace: Trace): ZIO[Any, Nothing, Unit] = Promise.make[Nothing, Unit].flatMap { drained =>
      state.modify[UIO[Unit]] {
        case open: PoolState.Open =>
          val remaining = open.held
          val closeAll = drained.succeed(()).when(remaining == 0) *>
            ZIO.foreachDiscard(open.waiters.values)(_.interrupt) *>
            ZIO.foreachParDiscard(open.idle)(close) *>
            drained.await
          (closeAll, PoolState.Closed(remaining, drained))
        case closed: PoolState.Closed => (closed.drained.await, closed)
      }.flatten
    }

    override def get(implicit trace: Trace) = ZIO.uninterruptibleMask { restore =>
      for {
        c <- acquireSlot(restore)(takeConnection(restore))
        _ <- inUse.increment
        proxy = new ConnectionProxy(c.connection, resetTimeoutMillis)
        _ <- ZIO.addFinalizerExit(checkin(c, proxy, _))
      } yield proxy
    }

    // Holding a slot, reuse an idle connection or create one, also after
    // shutdown has started. An expired idle connection is closed in the
    // background and the slot is kept for the retry.
    //
    // By design a request that got its slot just as shutdown started is not
    // stopped here and may open a new connection, which shutdown waits for and
    // closes at check-in. The window is tiny and shutdown only runs when the
    // application exits, so failing the request instead is not worth the code.
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def takeConnection(
      restore: ZIO.InterruptibilityRestorer,
    )(implicit trace: Trace): ZIO[Any, DatabaseError.Connection, ConnectionWrapper] = take.flatMap {
      case Some(c) =>
        connectionTooOld(c).flatMap { tooOld =>
          if (tooOld) discardInBackground(c) *> takeConnection(restore) else Exit.succeed(c)
        }
      case None => connect(restore)
    }

    // Take a free slot or join the queue for one, then run `withSlot`. A
    // waiter's promise completes when it is handed a slot, or is interrupted
    // when shutdown starts. A request counts as waiting from when it
    // gets or queues for a slot until `withSlot` finishes, so rejected requests
    // are never counted.
    private def acquireSlot[A](restore: ZIO.InterruptibilityRestorer)(
      withSlot: ZIO[Any, DatabaseError.Connection, A],
    )(implicit trace: Trace) =
      Promise.make[Nothing, Unit].flatMap { waiter =>
        def counted(effect: ZIO[Any, DatabaseError.Connection, A]) =
          waiting.increment *> effect.ensuring(waiting.decrement)
        state.modify[ZIO[Any, DatabaseError.Connection, A]] {
          case open: PoolState.Open if open.held < config.size =>
            (counted(withSlot), open.copy(held = open.held + 1))
          case open: PoolState.Open if open.waiters.sizeIs < config.queueSize =>
            val id = open.nextWaiter
            val queued = open.copy(waiters = open.waiters.updated(id, waiter), nextWaiter = id + 1)
            (counted(awaitSlot(id, waiter, restore) *> withSlot), queued)
          case open: PoolState.Open => (ZIO.fail(DatabaseError.Connection.Rejected(config.queueSize)), open)
          case closed: PoolState.Closed => (ZIO.fail(DatabaseError.Connection.Closed()), closed)
        }.flatten
      }

    // By design shutdown interrupts queued requests, as shutting down a ZIO
    // Queue interrupts the fibers waiting on it, rather than failing them with
    // Closed.
    private def awaitSlot(id: Long, waiter: Promise[Nothing, Unit], restore: ZIO.InterruptibilityRestorer)(implicit
      trace: Trace,
    ) = restore(waiter.await).onInterrupt(leaveQueue(id, waiter))

    // An interrupted waiter leaves the queue, or releases the slot it was handed
    // as it was interrupted. One interrupted by shutdown was handed no slot.
    private def leaveQueue(id: Long, waiter: Promise[Nothing, Unit])(implicit trace: Trace) = state.modify[UIO[Unit]] {
      case open: PoolState.Open if open.waiters.contains(id) =>
        (ZIO.unit, open.copy(waiters = open.waiters.removed(id)))
      case other => (waiter.await.exit.flatMap(granted => releaseSlot.when(granted.isSuccess)).unit, other)
    }.flatten

    // Hand the slot to the longest waiting request, or free it. After shutdown,
    // count down the slots shutdown waits for.
    private def releaseSlot(implicit trace: Trace) = state.modify[UIO[Unit]] {
      case open: PoolState.Open =>
        open.waiters.headOption match {
          case Some((id, waiter)) => (waiter.succeed(()).unit, open.copy(waiters = open.waiters.removed(id)))
          case None => (ZIO.unit, open.copy(held = open.held - 1))
        }
      case closed: PoolState.Closed =>
        (closed.drained.succeed(()).when(closed.remaining == 1).unit, closed.copy(remaining = closed.remaining - 1))
    }.flatten

    // Untracked by design, shutdown does not wait for these closes as it only
    // runs when the application exits.
    //
    // Also by design a closing connection holds no slot and does not count
    // towards `size`, so for as long as a close takes the database can see more
    // than `size` connections. HikariCP does the same: it removes a connection
    // from the pool before closing it on another thread. `closeConnection`
    // bounds how long a close can take.
    private def discardInBackground(c: ConnectionWrapper)(implicit trace: Trace) =
      (invalidated.increment *> close(c)).forkDaemon.unit

    // Close expired idle connections, then sleep, until the pool is closed.
    // Connections that are checked out are discarded at check-in instead.
    private[ConnectionPool] def sweep(implicit trace: Trace): UIO[Unit] =
      sweepOnce.repeatWhile(identity).unit

    // Only the sleep is interruptible, so expired connections removed from
    // `idle` are always handed to their close fibers, rather than leaked when
    // the pool shuts down.
    private def sweepOnce(implicit trace: Trace) = zio.Clock.nanoTime.flatMap { now =>
      state.modify[UIO[Boolean]] {
        case open: PoolState.Open =>
          val (expired, live) = open.idle.partition(_.expired(now))
          val next = ZIO.foreachDiscard(expired)(discardInBackground) *> ZIO.sleep(sweepInterval).interruptible
          (next.as(true), if (expired.isEmpty) open else open.copy(idle = live))
        case closed: PoolState.Closed => (Exit.succeed(false), closed)
      }.flatten
    }.uninterruptible

    private def take(implicit trace: Trace) = state.modify[Option[ConnectionWrapper]] {
      case open: PoolState.Open =>
        open.idle match {
          case Nil => (None, open)
          case c :: rest => (Some(c), open.copy(idle = rest))
        }
      case closed: PoolState.Closed => (None, closed)
    }

    // The creator runs `create` on a daemon fiber that inherits checkout's
    // uninterruptibility and is never interrupted. The request owns the result:
    // if it stops waiting, it settles the result on another fiber once creation
    // finishes, so it does not wait for it.
    //
    // An interruption may come from `create` rather than the request, so a
    // result that is already done is settled straight away. Waiting can only
    // end with the result or an interruption, so a cause with a failure or
    // defect in it is the result, which the request has then received.
    private def connect(restore: ZIO.InterruptibilityRestorer)(implicit trace: Trace) = for {
      result <- Promise.make[DatabaseError.Connection, ConnectionWrapper]
      _ <- open.exit.flatMap(result.done(_)).forkDaemon
      c <- restore(result.await).onExit {
        case Exit.Success(_) => ZIO.unit
        case Exit.Failure(cause) if cause.isInterruptedOnly =>
          val settle = result.await.exit.flatMap(settleAttempt)
          result.isDone.flatMap(done => if (done) settle else settle.forkDaemon.unit)
        case Exit.Failure(_) => releaseSlot
      }
    } yield c

    // Settle a result its request stopped waiting for, then release its slot.
    // Queue the connection or close it during shutdown. Log failures, except
    // those caused solely by interruption.
    private def settleAttempt(exit: Exit[DatabaseError.Connection, ConnectionWrapper])(implicit trace: Trace) =
      exit.foldCauseZIO(
        cause => ZIO.logWarningCause("Could not create connection", cause).unless(cause.isInterruptedOnly).unit,
        putIdle,
      ) *> releaseSlot

    private def checkin(c: ConnectionWrapper, proxy: ConnectionProxy, exit: Exit[Any, Any])(implicit trace: Trace) = {

      val reset = logFailure("reset connection")(ZIO.attemptBlocking(proxy.close())) *>
        ZIO.succeed(proxy.reusable)

      val validate = ZIO.attemptBlocking(c.connection.isValid(validationTimeoutSeconds)).catchAllCause {
        cause => ZIO.logWarningCause("Could not validate connection", cause).as(false)
      }

      // After a failure, a connection that resets is validated before reuse.
      val failed = exit.isFailure
      val reuse =
        if (failed && config.alwaysInvalidateOnFailure) Exit.succeed(false)
        else
          connectionTooOld(c).flatMap { tooOld =>
            if (tooOld) Exit.succeed(false)
            else reset.flatMap(reusable => if (reusable && failed) validate else Exit.succeed(reusable))
          }

      reuse.flatMap { reusable =>
        if (reusable) putIdle(c)
        else ZIO.succeed(proxy.discard()) *> discardInBackground(c)
      }.ensuring(inUse.decrement *> releaseSlot)
    }

    private def putIdle(c: ConnectionWrapper)(implicit trace: Trace) = state.modify[UIO[Unit]] {
      case open: PoolState.Open => (ZIO.unit, open.copy(idle = c :: open.idle))
      case closed: PoolState.Closed => (close(c), closed)
    }.flatten

    // Inherit checkout's uninterruptibility through acquisition and publication.
    // attemptBlocking cannot cancel JDBC calls; interruption could discard a
    // connection before the pool takes cleanup ownership.
    //
    // Each connection gets a lifetime of 90-100% of the maximum, so connections
    // created together do not all expire together.
    private def open(implicit trace: Trace) = creating.increment *> (for {
      connection <- create
      acquired <- zio.Clock.nanoTime
      variance <- zio.Random.nextDoubleBetween(0.9, 1.0)
      c = new ConnectionWrapper(connection, acquired + (maxLifetimeNanos * variance).toLong)
      _ <- created.increment
    } yield c).ensuring(creating.decrement)

    private def close(c: ConnectionWrapper)(implicit trace: Trace) = closeConnection(c.connection)

    private def connectionTooOld(c: ConnectionWrapper)(implicit trace: Trace) =
      zio.Clock.nanoTime.map(c.expired)
  }

  // How often HikariCP's housekeeper runs.
  private val sweepInterval = Duration.ofSeconds(30)

  // The network timeout HikariCP sets on a connection before closing it.
  private val closeTimeoutMillis = 15000

  /**
   * Closes a connection, logging failures because no request can receive them.
   *
   * As HikariCP does, a network timeout is set first so the close cannot hang
   * on a dead connection. The close goes ahead if the timeout cannot be set.
   */
  private[zoobie] def closeConnection(connection: Connection)(implicit trace: Trace): ZIO[Any, Nothing, Unit] =
    logFailure("close connection")(ZIO.attemptBlocking {
      try {
        if (!connection.isClosed) connection.setNetworkTimeout(ConnectionProxy.directExecutor, closeTimeoutMillis)
      } catch {
        case NonFatal(_) => ()
      }
      connection.close()
    })

  private def logFailure(action: String)(effect: ZIO[Any, Throwable, Any])(implicit trace: Trace): ZIO[Any, Nothing, Unit] =
    effect.unit.catchAllCause(ZIO.logWarningCause(s"Could not $action", _))

  private sealed trait PoolState
  private object PoolState {
    // `idle` is a stack, most recently returned first. `held` counts slots in
    // use. `waiters` are requests waiting for a slot, keyed in arrival order by
    // `nextWaiter` so an interrupted one leaves cheaply.
    final case class Open(
      idle: List[ConnectionWrapper],
      held: Int,
      waiters: TreeMap[Long, Promise[Nothing, Unit]],
      nextWaiter: Long,
    ) extends PoolState
    // `remaining` counts the slots shutdown still waits for.
    final case class Closed(remaining: Int, drained: Promise[Nothing, Unit]) extends PoolState

    val empty: PoolState = Open(Nil, held = 0, waiters = TreeMap.empty, nextWaiter = 0L)
  }

  // `expiresAt` is the clock's nanoTime when the connection's lifetime ends.
  // nanoTime has an arbitrary origin and can wrap around, so two times are only
  // ever compared through their difference.
  private final class ConnectionWrapper(
    val connection: Connection,
    val expiresAt: Long,
  ) {
    def expired(now: Long): Boolean = expiresAt - now <= 0
  }

  /**
   * Converts a duration to whole JDBC timeout units, rounding up and capping at
   * `Int.MaxValue`. Zero and negative durations become zero (no timeout).
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
