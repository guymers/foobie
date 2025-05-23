// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.ContravariantSemigroupal
import cats.syntax.apply.*
import doobie.FPS
import doobie.FRS
import doobie.enumerated.Nullability.*
import doobie.free.preparedstatement.PreparedStatementIO
import doobie.free.resultset.ResultSetIO
import doobie.util.fragment.Elem
import doobie.util.fragment.Fragment

import java.sql.PreparedStatement
import java.sql.ResultSet
import scala.collection.immutable.ArraySeq

trait Write[A] { self =>
  def puts: ArraySeq[(Put[?], NullabilityKnown)]
  def length: Int = puts.length

  def values(a: A): Seq[Any]

  def unsafeSet(ps: PreparedStatement, i: Int, a: A): Unit
  def unsafeUpdate(rs: ResultSet, i: Int, a: A): Unit

  def set(a: A): PreparedStatementIO[Unit] = FPS.raw(unsafeSet(_, 1, a))
  def update(a: A): ResultSetIO[Unit] = FRS.raw(unsafeUpdate(_, 1, a))

  def contramap[B](f: B => A): Write[B] = new Write[B] {
    override val puts = self.puts
    override def values(b: B) = self.values(f(b))
    override def unsafeSet(ps: PreparedStatement, i: Int, b: B) = self.unsafeSet(ps, i, f(b))
    override def unsafeUpdate(rs: ResultSet, i: Int, b: B) = self.unsafeUpdate(rs, i, f(b))
  }

  def product[B](fb: Write[B]): Write[(A, B)] = new Write[(A, B)] {
    override val puts = self.puts ++ fb.puts
    override def values(ab: (A, B)) = self.values(ab._1) ++ fb.values(ab._2)
    override def unsafeSet(ps: PreparedStatement, i: Int, ab: (A, B)) = {
      self.unsafeSet(ps, i, ab._1)
      fb.unsafeSet(ps, i + self.length, ab._2)
    }
    override def unsafeUpdate(rs: ResultSet, i: Int, ab: (A, B)) = {
      self.unsafeUpdate(rs, i, ab._1)
      fb.unsafeUpdate(rs, i + self.length, ab._2)
    }
  }

  /**
   * Given a value of type `A` and an appropriately parameterized SQL string we
   * can construct a `Fragment`. If `sql` is unspecified a comma-separated list
   * of `length` placeholders will be used.
   */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def toFragment(a: A, sql: String = List.fill(length)("?").mkString(",")): Fragment = {
    val elems: List[Elem] = (puts.toList zip values(a)).map {
      case ((p: Put[a], NoNulls), a) => Elem.Arg(a.asInstanceOf[a], p)
      case ((p: Put[a], Nullable), a) => Elem.Opt(a.asInstanceOf[Option[a]], p)
    }
    Fragment(sql, elems, None)
  }

}

object Write extends Write1 {

  def apply[A](implicit A: Write[A]): Write[A] = A

  implicit def tuple2[A, B](implicit A: Write[A], B: Write[B]): Write[(A, B)] = (A, B).tupled
  implicit def tuple3[A, B, C](implicit A: Write[A], B: Write[B], C: Write[C]): Write[(A, B, C)] = (A, B, C).tupled
  implicit def tuple4[A, B, C, D](implicit A: Write[A], B: Write[B], C: Write[C], D: Write[D]): Write[(A, B, C, D)] =
    (A, B, C, D).tupled
  implicit def tuple5[A, B, C, D, E](implicit
    A: Write[A],
    B: Write[B],
    C: Write[C],
    D: Write[D],
    E: Write[E],
  ): Write[(A, B, C, D, E)] = (A, B, C, D, E).tupled
  implicit def tuple6[A, B, C, D, E, F](implicit
    A: Write[A],
    B: Write[B],
    C: Write[C],
    D: Write[D],
    E: Write[E],
    F: Write[F],
  ): Write[(A, B, C, D, E, F)] = (A, B, C, D, E, F).tupled

  implicit val WriteContravariantSemigroupal: ContravariantSemigroupal[Write] = new ContravariantSemigroupal[Write] {
    override def contramap[A, B](fa: Write[A])(f: B => A) = fa.contramap(f)
    override def product[A, B](fa: Write[A], fb: Write[B]) = fa.product(fb)
  }

  implicit val unit: Write[Unit] = new Write[Unit] {
    override val puts = ArraySeq.empty
    override def values(a: Unit) = Seq.empty
    override def unsafeSet(ps: PreparedStatement, i: Int, a: Unit) = ()
    override def unsafeUpdate(rs: ResultSet, i: Int, a: Unit) = ()
  }

  implicit def fromPut[A](implicit P: Put[A]): Write[A] = new Write[A] {
    override val puts = ArraySeq((P, NoNulls))
    override def values(a: A) = Seq(a)
    override def unsafeSet(ps: PreparedStatement, i: Int, a: A) = P.unsafeSetNonNullable(ps, i, a)
    override def unsafeUpdate(rs: ResultSet, i: Int, a: A) = P.unsafeUpdateNonNullable(rs, i, a)
  }

  implicit def fromPutOption[A](implicit P: Put[A]): Write[Option[A]] = new Write[Option[A]] {
    override val puts = ArraySeq((P, Nullable))
    override def values(a: Option[A]) = Seq(a)
    override def unsafeSet(ps: PreparedStatement, i: Int, a: Option[A]) = P.unsafeSetNullable(ps, i, a)
    override def unsafeUpdate(rs: ResultSet, i: Int, a: Option[A]) = P.unsafeUpdateNullable(rs, i, a)
  }
}

sealed trait Write1 extends WritePlatform { this: Write.type =>

  implicit def optional[A](implicit W: Write[A], ev: A <:!< Option[?]): Write[Option[A]] = {
    val _ = ev

    new Write[Option[A]] {
      override val puts = W.puts.map { case (g, _) => (g, Nullable) }
      override def values(a: Option[A]) = a match {
        case None => puts.map(_ => None)
        case Some(a) => W.values(a).map {
            case o: Option[t] => o
            case o => Option(o)
          }
      }
      override def unsafeSet(ps: PreparedStatement, i: Int, a: Option[A]) = a match {
        case None => W.puts.zipWithIndex.foreach { case ((p, _), o) => p.unsafeSetNullable(ps, i + o, None) }
        case Some(a) => W.unsafeSet(ps, i, a)
      }
      override def unsafeUpdate(rs: ResultSet, i: Int, a: Option[A]) = a match {
        case None => W.puts.zipWithIndex.foreach { case ((p, _), o) => p.unsafeUpdateNullable(rs, i + o, None) }
        case Some(a) => W.unsafeUpdate(rs, i, a)
      }
    }
  }
}
