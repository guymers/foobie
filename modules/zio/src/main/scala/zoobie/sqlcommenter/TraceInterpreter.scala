package zoobie.sqlcommenter

import cats.~>
import doobie.free.connection.ConnectionOp
import zio.Task
import zio.ZIO

object TraceInterpreter {

  def create(
    i: ConnectionOp ~> Task,
    currentSpan: ZIO[Any, Nothing, Option[io.opentelemetry.api.trace.Span]],
  ): ConnectionOp ~> Task = {

    new (ConnectionOp ~> Task) {
      import ConnectionOp.*
      override def apply[A](fa: ConnectionOp[A]) = fa match {
        case op: Raw[A] => i(op)
        case op: Delay[A] => i(op)
        case op: RaiseError[A] => i(op)
        case op: HandleErrorWith[A] => i(op)

        case op @ WithPreparedStatement(sql, _, _) =>
          for {
            sql_ <- currentSpan.map {
              case None => sql
              case Some(span) =>
                val ctx = SQLCommenter.Trace.fromOpenTelemetryContext(span.getSpanContext)
                val state = SQLCommenter(controller = None, action = None, framework = None, ctx)
                SQLCommenter.affix(state, sql)
            }
            result <- i(op.copy(sql = sql_))
          } yield result
      }
    }
  }

}
