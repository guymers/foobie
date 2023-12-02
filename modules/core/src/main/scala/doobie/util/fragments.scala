// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie
package util

import cats.Id
import cats.Reducible
import cats.syntax.alternative.*
import cats.syntax.foldable.*
import cats.syntax.reducible.*
import doobie.syntax.string.*

import scala.reflect.ClassTag

/** Module of `Fragment` constructors. */
object fragments {

  /** Returns `ANY(fa0, fa1, ...)` */
  def any[F[_]: Reducible, A: ClassTag](fa: F[A])(implicit P: Put[Array[A]]): Fragment =
    fr"ANY(${Array.from(fa.toIterable)})"

  /**
   * Returns `?, ?, ?, ...` if `a` corresponds to one element or `(?,?,...),
   * (?,?,...), ...)` if `a` corresponds to more than one element
   */
  def commas[F[_]: Reducible, A](fa: F[A])(implicit W: Write[A]): Fragment = {
    val sql = if (W.length == 1) "?" else List.fill(W.length)("?").foldSmash("(", ",", ")")
    val f = W.toFragment(_, sql)
    fa.reduceLeftTo(f) { case (fragment, v) => fr"$fragment, ${f(v)}" }
  }

  /**
   * Same as [[commas]] but avoid callers having to provide explicit types for
   * `F` and `A` when using [[Id]].
   */
  def commasId[A: Write](fa: Id[A]): Fragment = commas(fa)

  /** Returns `(f1) AND (f2) AND ... (fn)`. */
  def and(fs: Fragment*): Fragment =
    fs.toList.map(parentheses).intercalate(fr"AND")

  /** Returns `(f1) AND (f2) AND ... (fn)` for all defined fragments. */
  def andOpt(fs: Option[Fragment]*): Fragment =
    and(fs.toList.unite*)

  /** Returns `(f1) OR (f2) OR ... (fn)`. */
  def or(fs: Fragment*): Fragment =
    fs.toList.map(parentheses).intercalate(fr"OR")

  /** Returns `(f1) OR (f2) OR ... (fn)` for all defined fragments. */
  def orOpt(fs: Option[Fragment]*): Fragment =
    or(fs.toList.unite*)

  /**
   * Returns `WHERE (f1) AND (f2) AND ... (fn)` or the empty fragment if `fs` is
   * empty.
   */
  def whereAnd(fs: Fragment*): Fragment =
    if (fs.isEmpty) Fragment.empty else fr"WHERE" ++ and(fs*)

  /**
   * Returns `WHERE (f1) AND (f2) AND ... (fn)` for defined `f`, if any,
   * otherwise the empty fragment.
   */
  def whereAndOpt(fs: Option[Fragment]*): Fragment =
    whereAnd(fs.toList.unite*)

  /**
   * Returns `WHERE (f1) OR (f2) OR ... (fn)` or the empty fragment if `fs` is
   * empty.
   */
  def whereOr(fs: Fragment*): Fragment =
    if (fs.isEmpty) Fragment.empty else fr"WHERE" ++ or(fs*)

  /**
   * Returns `WHERE (f1) OR (f2) OR ... (fn)` for defined `f`, if any, otherwise
   * the empty fragment.
   */
  def whereOrOpt(fs: Option[Fragment]*): Fragment =
    whereOr(fs.toList.unite*)

  /** Returns `(f)`. */
  def parentheses(f: Fragment): Fragment = fr0"(" ++ f ++ fr")"

  /** Returns `?,?,...,?` for the values in `a`. */
  def values[A](a: A)(implicit W: Write[A]): Fragment =
    W.toFragment(a)

}
