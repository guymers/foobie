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
import doobie.syntax.foldable.*
import doobie.syntax.string.*

import scala.annotation.nowarn
import scala.reflect.ClassTag

/** Module of `Fragment` constructors. */
@nowarn("msg=.*possible missing interpolator: detected an interpolated expression.*")
object fragments {

  /** Returns `ANY(fa0, fa1, ...)` */
  def any[F[_]: Reducible, A: ClassTag](fa: F[A])(implicit P: Put[Array[A]]): Fragment =
    fr"ANY(${Array.from(fa.toIterable)})"

  /** Returns `ANY(fa0, fa1, ...)` */
  @deprecated("Using `any` instead to force handling a non-empty collection", "0.14.3")
  def anyIterable[A: ClassTag](fa: Iterable[A])(implicit P: Put[Array[A]]): Fragment =
    fr"ANY(${Array.from(fa)})"

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

  /** Returns `VALUES (fs0), (fs1), ...`. */
  @deprecated("Replace with fr\"VALUES ${commas(fs)}\"", "0.14.0")
  def values[F[_]: Reducible, A](fs: F[A])(implicit w: Write[A]): Fragment =
    fs.toList.map(a => fr0"(${w.toFragment(a)})").foldSmash1(fr0"VALUES ", fr",", fr"")

  /** Returns `f IN (fs0, fs1, ...)`. */
  @deprecated("Replace with fr\"IN (${commas(fs)})\"", "0.14.0")
  def in[F[_]: Reducible, A: Put](f: Fragment, fs: F[A]): Fragment =
    fs.toList.map(a => fr0"$a").foldSmash1(f ++ fr0"IN (", fr",", fr")")

  /** Returns `f IN ((fs0-A, fs0-B), (fs1-A, fs1-B), ...)`. */
  @deprecated("Replace with fr\"IN (${commas(fs)})\"", "0.14.0")
  def in[F[_]: Reducible, A: Put, B: Put](f: Fragment, fs: F[(A, B)]): Fragment =
    fs.toList.map { case (a, b) => fr0"($a,$b)" }.foldSmash1(f ++ fr0"IN (", fr",", fr")")

  /** Returns `f NOT IN (fs0, fs1, ...)`. */
  @deprecated("Replace with fr\"NOT IN (${commas(fs)})\"", "0.14.0")
  def notIn[F[_]: Reducible, A: Put](f: Fragment, fs: F[A]): Fragment =
    fs.toList.map(a => fr0"$a").foldSmash1(f ++ fr0"NOT IN (", fr",", fr")")

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

  /** Returns `SET f1, f2, ... fn` or the empty fragment if `fs` is empty. */
  @deprecated("Replace with fr\"SET ${fs.toList.intercalate(fr\",\")}\"", "0.14.0")
  def set(fs: Fragment*): Fragment =
    if (fs.isEmpty) Fragment.empty else fr"SET" ++ fs.toList.intercalate(fr",")

  /**
   * Returns `SET f1, f2, ... fn` for defined `f`, if any, otherwise the empty
   * fragment.
   */
  @deprecated("Replace with fr\"SET ${fs.toList.intercalate(fr\",\")}\"", "0.14.0")
  def setOpt(fs: Option[Fragment]*): Fragment =
    set(fs.toList.unite*)

  /** Returns `(f)`. */
  def parentheses(f: Fragment): Fragment = fr0"(" ++ f ++ fr")"

  /** Returns `?,?,...,?` for the values in `a`. */
  def values[A](a: A)(implicit W: Write[A]): Fragment =
    W.toFragment(a)

}
