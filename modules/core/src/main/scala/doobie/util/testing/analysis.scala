// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util.testing

import cats.effect.kernel.Sync
import cats.syntax.show.*
import doobie.free.connection.ConnectionIO
import doobie.util.Colors
import doobie.util.analysis.*
import doobie.util.pos.Pos
import doobie.util.pretty.*
import doobie.util.query.Query
import doobie.util.query.Query0
import doobie.util.transactor.Transactor
import doobie.util.update.Update
import doobie.util.update.Update0
import org.tpolecat.typename.*

trait UnsafeRun[F[_]] {
  def unsafeRunSync[A](fa: F[A]): A
}

/**
 * Common base trait for various checkers and matchers.
 */
trait CheckerBase[M[_]] {
  // Effect type, required instances
  implicit def M: Sync[M]
  implicit def U: UnsafeRun[M]
  def transactor: Transactor[M]
  def colors: Colors = Colors.Ansi
}

/** Common data for all query-like types. */
final case class AnalysisArgs(
  typeName: String,
  pos: Option[Pos],
  sql: String,
  analysis: ConnectionIO[Analysis],
) {
  val cleanedSql = Block(
    sql.linesIterator
      .map(_.trim)
      .filterNot(_.isEmpty)
      .toList,
  )

  private val location =
    pos
      .map(f => show"${f.file}:${f.line}")
      .getOrElse("(source location unknown)")

  val header: String = show"$typeName defined at $location"
}

/** Information from [[doobie.util.analysis.Analysis]], prepared for output. */
final case class AnalysisReport(
  header: String,
  sql: Block,
  items: List[AnalysisReport.Item],
) {
  val succeeded: Boolean = items.forall(_.error.isEmpty)
}

object AnalysisReport {
  final case class Item(description: String, error: Option[Block])
}

/** Typeclass for query-like objects. */
trait Analyzable[T] {
  def unpack(t: T): AnalysisArgs
}

object Analyzable {
  def apply[T](implicit ev: Analyzable[T]): Analyzable[T] = ev

  def unpack[T](t: T)(implicit T: Analyzable[T]): AnalysisArgs =
    T.unpack(t)

  def instance[T](
    impl: T => AnalysisArgs,
  ): Analyzable[T] =
    new Analyzable[T] {
      def unpack(t: T) = impl(t)
    }

  implicit def analyzableQuery[A: TypeName, B: TypeName]: Analyzable[Query[A, B]] =
    instance { q =>
      AnalysisArgs(
        s"Query[${typeName[A]}, ${typeName[B]}]",
        q.pos,
        q.sql,
        q.analysis,
      )
    }

  implicit def analyzableQuery0[A: TypeName]: Analyzable[Query0[A]] =
    instance { q =>
      AnalysisArgs(
        s"Query0[${typeName[A]}]",
        q.pos,
        q.sql,
        q.analysis,
      )
    }

  implicit def analyzableUpdate[A: TypeName]: Analyzable[Update[A]] =
    instance { q =>
      AnalysisArgs(
        s"Update[${typeName[A]}]",
        q.pos,
        q.sql,
        q.analysis,
      )
    }

  implicit val analyzableUpdate0: Analyzable[Update0] =
    instance { q =>
      AnalysisArgs(
        s"Update0",
        q.pos,
        q.sql,
        q.analysis,
      )
    }
}
