// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.syntax

import cats.syntax.foldable.*
import doobie.syntax.SqlInterpolator.SingleFragment
import doobie.util.Write
import doobie.util.fragment.Fragment
import doobie.util.pos.Pos

import scala.language.implicitConversions

/**
 * String interpolator for SQL literals. An expression of the form `sql".. \$a
 * ... \$b ..."` with interpolated values of type `A` and `B` (which must have
 * instances of `Put`) yields a value of type [[Fragment]].
 */
final class SqlInterpolator(private val sc: StringContext) extends AnyVal {

  private def mkFragment(parts: List[SingleFragment[?]], token: Boolean, pos: Pos): Fragment = {
    val last = if (token) Fragment(" ", Nil, None) else Fragment.empty

    sc.parts.toList
      .map(sql => SingleFragment(Fragment(sql, Nil, Some(pos))))
      .zipAll(parts, SingleFragment.empty, SingleFragment(last))
      .flatMap { case (a, b) => List(a.fr, b.fr) }
      .combineAll
  }

  /**
   * Interpolator for a statement fragment that can contain interpolated values.
   * When inserted into the final SQL statement this fragment will be followed
   * by a space. This is normally what you want, and it makes it easier to
   * concatenate fragments because you don't need to think about intervening
   * whitespace. If you do not want this behavior, use `fr0`.
   */
  def fr(a: SingleFragment[?]*)(implicit pos: Pos) = mkFragment(a.toList, token = true, pos)

  /** Alternative name for the `fr0` interpolator. */
  def sql(a: SingleFragment[?]*)(implicit pos: Pos) = mkFragment(a.toList, token = false, pos)

  /**
   * Interpolator for a statement fragment that can contain interpolated values.
   * Unlike `fr` no attempt is made to be helpful with respect to whitespace.
   */
  def fr0(a: SingleFragment[?]*)(implicit pos: Pos) = mkFragment(a.toList, token = false, pos)

}

object SqlInterpolator {
  final case class SingleFragment[+A](fr: Fragment) extends AnyVal
  object SingleFragment {
    val empty = SingleFragment(Fragment.empty)

    implicit def fromFragment(fr: Fragment): SingleFragment[Nothing] = SingleFragment(fr)
    implicit def fromWrite[A](a: A)(implicit write: Write[A]): SingleFragment[A] = SingleFragment(write.toFragment(a))
  }
}

trait ToSqlInterpolator {
  implicit def toSqlInterpolator(sc: StringContext): SqlInterpolator =
    new SqlInterpolator(sc)
}

object string extends ToSqlInterpolator
