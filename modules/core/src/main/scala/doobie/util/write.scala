// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.ContravariantSemigroupal
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
      fb.unsafeSet(ps, i, ab._2)
    }
    override def unsafeUpdate(rs: ResultSet, i: Int, ab: (A, B)) = {
      self.unsafeUpdate(rs, i, ab._1)
      fb.unsafeUpdate(rs, i, ab._2)
    }
  }

  /**
   * Given a value of type `A` and an appropriately parameterized SQL string we
   * can construct a `Fragment`. If `sql` is unspecified a comma-separated list
   * of `length` placeholders will be used.
   */
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

  implicit val WriteContravariantSemigroupal: ContravariantSemigroupal[Write] = new ContravariantSemigroupal[Write] {
    override def contramap[A, B](fa: Write[A])(f: B => A) = fa.contramap(f)
    override def product[A, B](fa: Write[A], fb: Write[B]) = fa.product(fb)
  }

  implicit val unitComposite: Write[Unit] = new Write[Unit] {
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
        case Some(a) => W.values(a)
      }
      override def unsafeSet(ps: PreparedStatement, i: Int, a: Option[A]) = {
        a.foreach(W.unsafeSet(ps, i, _))
      }
      override def unsafeUpdate(rs: ResultSet, i: Int, a: Option[A]) = {
        a.foreach(W.unsafeUpdate(rs, i, _))
      }
    }
  }
}
