// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.Apply
import cats.syntax.apply.*
import doobie.FRS
import doobie.enumerated.Nullability
import doobie.enumerated.Nullability.*
import doobie.free.resultset.ResultSetIO

import java.sql.ResultSet
import scala.annotation.implicitNotFound
import scala.collection.immutable.ArraySeq
import scala.collection.mutable

@implicitNotFound("""
Cannot find or construct a Read instance for type:

  ${A}

This can happen for a few reasons, but the most common case is that a data
member somewhere within this type doesn't have a Get instance in scope. Here are
some debugging hints:

- For auto derivation ensure `doobie.util.Read.Auto.*` is being imported
- For Option types, ensure that a Read instance is in scope for the non-Option version.
- For types you expect to map to a single column ensure that a Get instance is in scope.
- For case classes and tuples ensure that each element has a Read instance in scope.
- Lather, rinse, repeat, recursively until you find the problematic bit.

You can check that an instance exists for Read in the REPL or in your code:

  scala> Read[Foo]

and similarly with Get:

  scala> Get[Foo]

And find the missing instance and construct it as needed. Refer to Chapter 12
of the book of doobie for more information.
""")
trait Read[A] { self =>
  def gets: ArraySeq[(Get[?], NullabilityKnown)]
  def length: Int = gets.length

  def unsafeGet(rs: ResultSet, i: Int): A

  final def get: ResultSetIO[A] = FRS.raw(unsafeGet(_, 1))

  final def map[B](f: A => B): Read[B] = new Read[B] {
    override val gets = self.gets
    override def unsafeGet(rs: ResultSet, i: Int) = f(self.unsafeGet(rs, i))
  }

  final def ap[B](ff: Read[A => B]): Read[B] = new Read[B] {
    override val gets = ff.gets ++ self.gets
    override def unsafeGet(rs: ResultSet, i: Int) = ff.unsafeGet(rs, i)(self.unsafeGet(rs, i + ff.gets.size))
  }

  final def map2[B, Z](fb: Read[B])(f: (A, B) => Z): Read[Z] = new Read[Z] {
    override val gets = self.gets ++ fb.gets
    override def unsafeGet(rs: ResultSet, i: Int) = f(self.unsafeGet(rs, i), fb.unsafeGet(rs, i + self.gets.size))
  }

  final def product[B](fb: Read[B]): Read[(A, B)] = map2(fb) { case t @ (_, _) => t }
}

object Read extends Read1 {

  def apply[A](implicit ev: Read[A]): ev.type = ev

  implicit def tuple2[A, B](implicit A: Read[A], B: Read[B]): Read[(A, B)] = (A, B).tupled
  implicit def tuple3[A, B, C](implicit A: Read[A], B: Read[B], C: Read[C]): Read[(A, B, C)] = (A, B, C).tupled
  implicit def tuple4[A, B, C, D](implicit A: Read[A], B: Read[B], C: Read[C], D: Read[D]): Read[(A, B, C, D)] =
    (A, B, C, D).tupled
  implicit def tuple5[A, B, C, D, E](implicit
    A: Read[A],
    B: Read[B],
    C: Read[C],
    D: Read[D],
    E: Read[E],
  ): Read[(A, B, C, D, E)] = (A, B, C, D, E).tupled
  implicit def tuple6[A, B, C, D, E, F](implicit
    A: Read[A],
    B: Read[B],
    C: Read[C],
    D: Read[D],
    E: Read[E],
    F: Read[F],
  ): Read[(A, B, C, D, E, F)] = (A, B, C, D, E, F).tupled

  object Auto extends ReadAutoPlatform

  implicit val ReadApply: Apply[Read] = new Apply[Read] {
    override def map[A, B](fa: Read[A])(f: A => B) = fa.map(f)
    override def ap[A, B](ff: Read[A => B])(fa: Read[A]) = fa.ap(ff)
    override def map2[A, B, Z](fa: Read[A], fb: Read[B])(f: (A, B) => Z) = fa.map2(fb)(f)
    override def product[A, B](fa: Read[A], fb: Read[B]) = fa.product(fb)
  }

  implicit val unit: Read[Unit] = new Read[Unit] {
    override val gets = ArraySeq.empty
    override def unsafeGet(rs: ResultSet, i: Int) = ()
  }

  implicit def fromGet[A](implicit G: Get[A]): Read[A] = new Read[A] {
    override val gets = ArraySeq((G, NoNulls))
    override def unsafeGet(rs: ResultSet, i: Int) = G.unsafeGetNonNullable(rs, i)
  }

  implicit def fromGetOption[A](implicit G: Get[A], ev: A <:!< Option[?]): Read[Option[A]] = {
    val _ = ev

    new Read[Option[A]] {
      override val gets = ArraySeq((G, Nullable))
      override def unsafeGet(rs: ResultSet, i: Int) = G.unsafeGetNullable(rs, i)
    }
  }

  @SuppressWarnings(Array(
    "org.wartremover.warts.MutableDataStructures",
    "org.wartremover.warts.Var",
    "org.wartremover.warts.While",
  ))
  private[doobie] def build(instances: Iterable[Read[?]])(rs: ResultSet, index: Int) = {
    var i = index
    val arr = mutable.ArrayBuilder.make[Any]
    val it = instances.iterator
    while (it.hasNext) {
      val instance = it.next()
      val a = instance.unsafeGet(rs, i)
      val _ = arr.addOne(a)
      i = i + instance.gets.size
    }
    arr.result()
  }
}

sealed trait Read1 extends ReadPlatform { this: Read.type =>

  implicit def optional[A](implicit R: Read[A], ev: A <:!< Option[?]): Read[Option[A]] = {
    val _ = ev

    new Read[Option[A]] {
      override val gets = R.gets.map { case (g, _) => (g, Nullable) }
      @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
      override def unsafeGet(rs: ResultSet, i: Int) = {
        // if first item is null or gets is empty => return None
        var allNull = true
        var nonNullableIsNull = false

        var _i = i
        val iterator = R.gets.iterator
        while (iterator.hasNext && !nonNullableIsNull) {
          val (get, n) = iterator.next()
          get.unsafeGetNullable(rs, _i) match {
            case None =>
              if (n == Nullability.NoNulls) nonNullableIsNull = true
            case Some(_) =>
              allNull = false
          }
          _i = _i + 1
        }

        if (allNull || nonNullableIsNull) None else Option(R.unsafeGet(rs, i))
      }
    }
  }
}
