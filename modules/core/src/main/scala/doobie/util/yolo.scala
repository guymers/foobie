// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.show.*
import doobie.free.connection.ConnectionIO
import doobie.free.connection.delay
import doobie.syntax.connectionio.*
import doobie.util.query.Query
import doobie.util.query.Query0
import doobie.util.testing.AnalysisArgs
import doobie.util.testing.Analyzable
import doobie.util.testing.analyze
import doobie.util.testing.formatReport
import doobie.util.transactor.Transactor
import doobie.util.update.Update
import doobie.util.update.Update0
import fs2.Stream
import org.tpolecat.typename.*

/** Module for implicit syntax useful in REPL session. */
object yolo {

  class Yolo[M[_]](xa: Transactor[M])(implicit ev: MonadCancelThrow[M]) {

    private def out(s: String, colors: Colors): ConnectionIO[Unit] =
      delay(Console.println(show"${colors.BLUE}  $s${colors.RESET}"))

    implicit class Query0YoloOps[A: TypeName](q: Query0[A]) {

      @SuppressWarnings(Array("org.wartremover.warts.ToString"))
      def quick(implicit colors: Colors = Colors.Ansi): M[Unit] =
        q.stream
          .map(_.toString)
          .evalMap(out(_, colors))
          .compile
          .drain
          .transact(xa)(ev)

      def check(implicit colors: Colors = Colors.Ansi): M[Unit] =
        checkImpl(Analyzable.unpack(q), colors)

      def checkOutput(implicit colors: Colors = Colors.Ansi): M[Unit] =
        checkImpl(
          AnalysisArgs(
            show"Query0[${typeName[A]}]",
            q.pos,
            q.sql,
            q.outputAnalysis,
          ),
          colors,
        )
    }

    implicit class QueryYoloOps[I: TypeName, A: TypeName](q: Query[I, A]) {

      def quick(i: I): M[Unit] =
        q.toQuery0(i).quick

      def check(implicit colors: Colors = Colors.Ansi): M[Unit] =
        checkImpl(Analyzable.unpack(q), colors)

      def checkOutput(implicit colors: Colors = Colors.Ansi): M[Unit] =
        checkImpl(
          AnalysisArgs(
            show"Query[${typeName[I]}, ${typeName[A]}]",
            q.pos,
            q.sql,
            q.outputAnalysis,
          ),
          colors,
        )
    }

    implicit class Update0YoloOps(u: Update0) {

      def quick(implicit colors: Colors = Colors.Ansi): M[Unit] =
        u.run.flatMap(a => out(show"$a row(s) updated", colors)).transact(xa)(ev)

      def check(implicit colors: Colors = Colors.Ansi): M[Unit] =
        checkImpl(Analyzable.unpack(u), colors)
    }

    implicit class UpdateYoloOps[I: TypeName](u: Update[I]) {

      def quick(i: I)(implicit colors: Colors = Colors.Ansi): M[Unit] =
        u.toUpdate0(i).quick

      def check(implicit colors: Colors = Colors.Ansi): M[Unit] =
        checkImpl(Analyzable.unpack(u), colors)
    }

    implicit class ConnectionIOYoloOps[A](ca: ConnectionIO[A]) {
      @SuppressWarnings(Array("org.wartremover.warts.ToString"))
      def quick(implicit colors: Colors = Colors.Ansi): M[Unit] =
        ca.flatMap(a => out(a.toString, colors)).transact(xa)(ev)
    }

    implicit class StreamYoloOps[A](pa: Stream[ConnectionIO, A]) {
      @SuppressWarnings(Array("org.wartremover.warts.ToString"))
      def quick(implicit colors: Colors = Colors.Ansi): M[Unit] =
        pa.evalMap(a => out(a.toString, colors)).compile.drain.transact(xa)(ev)
    }

    private def checkImpl(args: AnalysisArgs, colors: Colors): M[Unit] =
      analyze(args).flatMap { report =>
        val formatted = formatReport(args, report, colors)
        delay(println(formatted.padLeft("  ")))
      }.transact(xa)(ev)
  }
}
