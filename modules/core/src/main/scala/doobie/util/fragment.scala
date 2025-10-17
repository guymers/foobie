// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.Monoid
import cats.data.Chain
import doobie.enumerated.Nullability.*
import doobie.util.pos.Pos
import doobie.util.query.Query
import doobie.util.query.Query0
import doobie.util.update.Update
import doobie.util.update.Update0

import java.sql.PreparedStatement
import java.sql.ResultSet
import scala.collection.immutable.ArraySeq

/** Module defining the `Fragment` data type. */
object fragment {

  /**
   * A statement fragment, which may include interpolated values. Fragments can
   * be composed by concatenation, which maintains the correct offset and
   * mappings for interpolated values. Once constructed a `Fragment` is opaque;
   * it has no externally observable properties. Fragments are eventually used
   * to construct a [[Query0]] or [[Update0]].
   */
  final class Fragment(
    protected val sql: String,
    protected val elems: Chain[Elem],
    protected val pos: Option[Pos],
  ) {

    // Unfortunately we need to produce a Write for our list of elems, which is a bit of a grunt
    // but straightforward nonetheless. And it's stacksafe!
    private implicit lazy val write: Write[elems.type] = {
      import Elem.*

      new Write[elems.type] {
        override val puts = elems.map {
          case Arg(_, p) => (p, NoNulls)
          case Opt(_, p) => (p, Nullable)
        }.toList.to(ArraySeq)

        override def values(es: elems.type) = es.map {
          case Arg(a, _) => a
          case Opt(a, _) => a
        }.toList

        @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
        override def unsafeSet(ps: PreparedStatement, i: Int, es: elems.type) = {
          var index = i
          val it = es.iterator
          while (it.hasNext) {
            it.next() match {
              case Arg(a, p) => p.unsafeSetNonNullable(ps, index, a)
              case Opt(a, p) => p.unsafeSetNullable(ps, index, a)
            }
            index += 1
          }
        }

        @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
        override def unsafeUpdate(rs: ResultSet, i: Int, es: elems.type) = {
          var index = i
          val it = es.iterator
          while (it.hasNext) {
            it.next() match {
              case Arg(a, p) => p.unsafeUpdateNonNullable(rs, index, a)
              case Opt(a, p) => p.unsafeUpdateNullable(rs, index, a)
            }
            index += 1
          }
        }
      }

    }

    /** Concatenate this fragment with another, yielding a larger fragment. */
    def ++(fb: Fragment): Fragment =
      new Fragment(sql + fb.sql, elems ++ fb.elems, pos orElse fb.pos)

    @SuppressWarnings(Array("org.wartremover.warts.Overloading"))
    def stripMargin(marginChar: Char): Fragment =
      new Fragment(sql.stripMargin(marginChar), elems, pos)

    @SuppressWarnings(Array("org.wartremover.warts.Overloading"))
    def stripMargin: Fragment = stripMargin('|')

    /**
     * Construct a [[Query0]] from this fragment, with asserted row type `B`.
     */
    def query[B](implicit R: Read[B]): Query0[B] = Query[elems.type, B](sql, pos).toQuery0(elems)

    /** Construct an [[Update0]] from this fragment. */
    def update: Update0 = Update[elems.type](sql, pos)(write).toUpdate0(elems)

    override def toString =
      s"""Fragment("$sql")"""

    /**
     * Used only for testing; this pulls out the arguments as an untyped list.
     */
    private def args: List[Any] =
      elems.toList.map {
        case Elem.Arg(a, _) => a
        case Elem.Opt(a, _) => a
      }

    /**
     * Used only for testing; this uses universal equality on the captured
     * arguments.
     */
    @SuppressWarnings(Array("org.wartremover.warts.Equals"))
    private[util] def unsafeEquals(fb: Fragment): Boolean =
      sql == fb.sql && args == fb.args

    /** Internals of this fragment. */
    def internals: Fragment.Internals =
      new Fragment.Internals {
        type A = elems.type
        val arg = elems
        val write = Fragment.this.write
        val elements = args
        val pos = Fragment.this.pos
        val sql = Fragment.this.sql
      }

  }
  object Fragment {

    /**
     * Internals of a `Fragment`, available for diagnostic purposes. Monoidal
     * structure is *not* preserved for the elements of this object.
     */
    trait Internals {

      /** Existential type of this Fragment's argument (typically an HList). */
      type A

      /** This Fragment's argument, as an opaque value (typically an HList). */
      def arg: A

      /** A `Write` instance for `A`. */
      def write: Write[A]

      /** The elements of `a` as an untyped list. */
      def elements: List[Any]

      /** Source code position where this Fragment was constructed, if known. */
      def pos: Option[Pos]

      /** This `Fragment`'s SQL string. */
      def sql: String

    }

    /**
     * Construct a statement fragment with the given SQL string, which must
     * contain sufficient `?` placeholders to accommodate the given list of
     * interpolated elements. This is normally accomplished via the string
     * interpolator rather than direct construction.
     */
    @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
    def apply(sql: String, elems: List[Elem], pos: Option[Pos] = None): Fragment =
      new Fragment(sql, Chain.fromSeq(elems), pos)

    /**
     * Construct a statement fragment with no interpolated values and no
     * trailing space; the passed SQL string must not contain `?` placeholders.
     */
    @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
    def const0(sql: String, pos: Option[Pos] = None): Fragment =
      new Fragment(sql, Chain.empty, pos)

    /**
     * Construct a statement fragment with no interpolated values and a trailing
     * space; the passed SQL string must not contain `?` placeholders.
     */
    @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
    def const(sql: String, pos: Option[Pos] = None): Fragment =
      const0(sql + " ", pos)

    /** The empty fragment. Adding this to another fragment has no effect. */
    val empty: Fragment =
      const0("")

    /** Statement fragments form a monoid. */
    implicit val FragmentMonoid: Monoid[Fragment] =
      new Monoid[Fragment] {
        val empty = Fragment.empty
        def combine(a: Fragment, b: Fragment) = a ++ b
      }

  }

  sealed trait Elem
  object Elem {
    final case class Arg[A](a: A, p: Put[A]) extends Elem
    final case class Opt[A](a: Option[A], p: Put[A]) extends Elem
  }

}
