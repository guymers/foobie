//package zoobie.sqlcommenter
//
//import cats.data.Kleisli
//import zio.Task
//import zio.ZIO
//
//object TraceInterpreter {
//
//  def create(
//    i: KleisliInterpreter[Task],
//    currentSpan: ZIO[Any, Nothing, Option[io.opentelemetry.api.trace.Span]],
//  ): KleisliInterpreter[Task] = {
//
//    implicit val syncM: Sync[Task] = i.syncM
//
//    def addTraceInfo[A, B](sql: String, run: String => Kleisli[Task, A, B]): Kleisli[Task, A, B] = {
//      val a: Task[String] = currentSpan.map {
//        case None => sql
//        case Some(span) =>
//          val ctx = SQLCommenter.Trace.fromOpenTelemetryContext(span.getSpanContext)
//          val state = SQLCommenter(controller = None, action = None, framework = None, ctx)
//          SQLCommenter.affix(state, sql)
//      }
//      Kleisli.liftK(a).flatMap(run(_))
//    }
//
//  }
//
//}
