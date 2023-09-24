package zoobie.sqlcommenter

import cats.data.Kleisli
import cats.effect.kernel.Sync
import doobie.free.KleisliInterpreter
import zio.Task
import zio.ZIO

object TraceInterpreter {

  def create(
    i: KleisliInterpreter[Task],
    currentSpan: ZIO[Any, Nothing, Option[io.opentelemetry.api.trace.Span]],
  ): KleisliInterpreter[Task] = {

    implicit val syncM: Sync[Task] = i.syncM

    def addTraceInfo[A, B](sql: String, run: String => Kleisli[Task, A, B]): Kleisli[Task, A, B] = {
      val a: Task[String] = currentSpan.map {
        case None => sql
        case Some(span) =>
          val ctx = SQLCommenter.Trace.fromOpenTelemetryContext(span.getSpanContext)
          val state = SQLCommenter(controller = None, action = None, framework = None, ctx)
          SQLCommenter.affix(state, sql)
      }
      Kleisli.liftK(a).flatMap(run(_))
    }

    val TraceConnectionInterpreter = new i.ConnectionInterpreter {
      override def prepareCall(a: String) = addTraceInfo(a, super.prepareCall(_))
      override def prepareCall(a: String, b: Int, c: Int) = addTraceInfo(a, super.prepareCall(_, b, c))
      override def prepareCall(a: String, b: Int, c: Int, d: Int) = addTraceInfo(a, super.prepareCall(_, b, c, d))

      override def prepareStatement(a: String) = addTraceInfo(a, super.prepareStatement(_))
      override def prepareStatement(a: String, b: Array[Int]) = addTraceInfo(a, super.prepareStatement(_, b))
      override def prepareStatement(a: String, b: Array[String]) = addTraceInfo(a, super.prepareStatement(_, b))
      override def prepareStatement(a: String, b: Int) = addTraceInfo(a, super.prepareStatement(_, b))
      override def prepareStatement(a: String, b: Int, c: Int) = addTraceInfo(a, super.prepareStatement(_, b, c))
      override def prepareStatement(a: String, b: Int, c: Int, d: Int) =
        addTraceInfo(a, super.prepareStatement(_, b, c, d))
    }

    val TraceStatementInterpreter = new i.StatementInterpreter {
      override def execute(a: String) = addTraceInfo(a, super.execute(_))
      override def execute(a: String, b: Array[Int]) = addTraceInfo(a, super.execute(_, b))
      override def execute(a: String, b: Array[String]) = addTraceInfo(a, super.execute(_, b))
      override def execute(a: String, b: Int) = addTraceInfo(a, super.execute(_, b))

      override def executeLargeUpdate(a: String) = addTraceInfo(a, super.executeLargeUpdate(_))
      override def executeLargeUpdate(a: String, b: Array[Int]) = addTraceInfo(a, super.executeLargeUpdate(_, b))
      override def executeLargeUpdate(a: String, b: Array[String]) = addTraceInfo(a, super.executeLargeUpdate(_, b))
      override def executeLargeUpdate(a: String, b: Int) = addTraceInfo(a, super.executeLargeUpdate(_, b))

      override def executeQuery(a: String) = addTraceInfo(a, super.executeQuery(_))

      override def executeUpdate(a: String) = addTraceInfo(a, super.executeUpdate(_))
      override def executeUpdate(a: String, b: Array[Int]) = addTraceInfo(a, super.executeUpdate(_, b))
      override def executeUpdate(a: String, b: Array[String]) = addTraceInfo(a, super.executeUpdate(_, b))
      override def executeUpdate(a: String, b: Int) = addTraceInfo(a, super.executeUpdate(_, b))
    }

    val TracePreparedStatementInterpreter = new i.PreparedStatementInterpreter {
      override def execute(a: String) = addTraceInfo(a, super.execute(_))
      override def execute(a: String, b: Array[Int]) = addTraceInfo(a, super.execute(_, b))
      override def execute(a: String, b: Array[String]) = addTraceInfo(a, super.execute(_, b))
      override def execute(a: String, b: Int) = addTraceInfo(a, super.execute(_, b))

      override def executeLargeUpdate(a: String) = addTraceInfo(a, super.executeLargeUpdate(_))
      override def executeLargeUpdate(a: String, b: Array[Int]) = addTraceInfo(a, super.executeLargeUpdate(_, b))
      override def executeLargeUpdate(a: String, b: Array[String]) = addTraceInfo(a, super.executeLargeUpdate(_, b))
      override def executeLargeUpdate(a: String, b: Int) = addTraceInfo(a, super.executeLargeUpdate(_, b))

      override def executeQuery(a: String) = addTraceInfo(a, super.executeQuery(_))

      override def executeUpdate(a: String) = addTraceInfo(a, super.executeUpdate(_))
      override def executeUpdate(a: String, b: Array[Int]) = addTraceInfo(a, super.executeUpdate(_, b))
      override def executeUpdate(a: String, b: Array[String]) = addTraceInfo(a, super.executeUpdate(_, b))
      override def executeUpdate(a: String, b: Int) = addTraceInfo(a, super.executeUpdate(_, b))
    }

    val TraceCallableStatementInterpreter = new i.CallableStatementInterpreter {
      override def execute(a: String) = addTraceInfo(a, super.execute(_))
      override def execute(a: String, b: Array[Int]) = addTraceInfo(a, super.execute(_, b))
      override def execute(a: String, b: Array[String]) = addTraceInfo(a, super.execute(_, b))
      override def execute(a: String, b: Int) = addTraceInfo(a, super.execute(_, b))

      override def executeLargeUpdate(a: String) = addTraceInfo(a, super.executeLargeUpdate(_))
      override def executeLargeUpdate(a: String, b: Array[Int]) = addTraceInfo(a, super.executeLargeUpdate(_, b))
      override def executeLargeUpdate(a: String, b: Array[String]) = addTraceInfo(a, super.executeLargeUpdate(_, b))
      override def executeLargeUpdate(a: String, b: Int) = addTraceInfo(a, super.executeLargeUpdate(_, b))

      override def executeQuery(a: String) = addTraceInfo(a, super.executeQuery(_))

      override def executeUpdate(a: String) = addTraceInfo(a, super.executeUpdate(_))
      override def executeUpdate(a: String, b: Array[Int]) = addTraceInfo(a, super.executeUpdate(_, b))
      override def executeUpdate(a: String, b: Array[String]) = addTraceInfo(a, super.executeUpdate(_, b))
      override def executeUpdate(a: String, b: Int) = addTraceInfo(a, super.executeUpdate(_, b))
    }

    new KleisliInterpreter[Task] {
      override lazy val NClobInterpreter = i.NClobInterpreter
      override lazy val BlobInterpreter = i.BlobInterpreter
      override lazy val ClobInterpreter = i.ClobInterpreter
      override lazy val DatabaseMetaDataInterpreter = i.DatabaseMetaDataInterpreter
      override lazy val DriverInterpreter = i.DriverInterpreter
      override lazy val RefInterpreter = i.RefInterpreter
      override lazy val SQLDataInterpreter = i.SQLDataInterpreter
      override lazy val SQLInputInterpreter = i.SQLInputInterpreter
      override lazy val SQLOutputInterpreter = i.SQLOutputInterpreter
      override lazy val ConnectionInterpreter = TraceConnectionInterpreter
      override lazy val StatementInterpreter = TraceStatementInterpreter
      override lazy val PreparedStatementInterpreter = TracePreparedStatementInterpreter
      override lazy val CallableStatementInterpreter = TraceCallableStatementInterpreter
      override lazy val ResultSetInterpreter = i.ResultSetInterpreter
    }
  }

}
