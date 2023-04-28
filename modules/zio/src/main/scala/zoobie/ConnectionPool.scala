package zoobie

import zio.Exit
import zio.Ref
import zio.Scope
import zio.Trace
import zio.ZIO
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

    for {
      numQueuedRef <- Ref.make[Int](0)
      pool <- ZPool.make(conn, config.size)
      invalidate = (c: ConnectionWrapper) => pool.invalidate(c) <* invalidated.increment
    } yield {
      new ConnectionPool {
        override def get(implicit trace: Trace) = for {
          atQueueSize <- numQueuedRef.modify { i =>
            if (i >= config.queueSize) (true, config.queueSize) else (false, i + 1)
          }.uninterruptible
          _ <- ZIO.fail(DatabaseError.Connection.Rejected(config.queueSize)).when(atQueueSize).uninterruptible

          _ <- waiting.increment.uninterruptible
          c <- pool.get.onExit { _ =>
            waiting.decrement *> numQueuedRef.update(_ - 1)
          }

          _ <- ZIO.acquireRelease(inUse.increment)(_ => inUse.decrement)

          _ <- ZIO.addFinalizerExit {
            case Exit.Success(_) => for {
                now <- zio.Clock.nanoTime
                age = (now - c.acquired).nanoseconds
                maxLifetimeJitter <- zio.Random.nextDoubleBetween(0.9, 1.1)
                maxLifetime = (config.maxConnectionLifetime.toNanos * maxLifetimeJitter).toLong.nanoseconds
                _ <- invalidate(c).when(age > maxLifetime)
              } yield ()

            case Exit.Failure(_) =>
              invalidate(c).unlessZIO {
                c.isValid(config.validationTimeout).catchAll(_ => ZIO.succeed(false))
              }
          }
        } yield c.connection
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
