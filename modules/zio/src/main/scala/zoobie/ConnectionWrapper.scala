package zoobie

import zio.Scope
import zio.Trace
import zio.ZIO

import java.sql.Connection
import java.time.Duration

class ConnectionWrapper(val connection: Connection, val acquired: Long) {

  def close(implicit trace: Trace): ZIO[Any, Throwable, Unit] = {
    ZIO.attemptBlocking {
      connection.close()
    }
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
