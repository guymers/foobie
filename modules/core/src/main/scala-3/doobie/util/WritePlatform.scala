// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import java.sql.PreparedStatement
import java.sql.ResultSet
import scala.collection.immutable.ArraySeq
import scala.compiletime.erasedValue
import scala.compiletime.summonInline
import scala.deriving.Mirror

trait WritePlatform {

  inline def summonAll[T <: Tuple]: List[Write[?]] = inline erasedValue[T] match {
    case _: EmptyTuple => Nil
    case _: (t *: ts) => summonInline[Write[t]] :: summonAll[ts]
  }

  inline def derived[A](using m: Mirror.ProductOf[A]): Write[A] = {
    lazy val typeclasses = summonAll[m.MirroredElemTypes]
    new WritePlatformInstance[A](typeclasses)
  }
}

class WritePlatformInstance[A](typeclasses: => List[Write[?]]) extends Write[A] {
  private lazy val instances = typeclasses
  override lazy val puts = instances.to(ArraySeq).flatMap(_.puts)
  override def values(a: A) = {
    instances.zipWithIndex.flatMap { case (w: Write[a], i) =>
      val v = a.asInstanceOf[Product].productElement(i).asInstanceOf[a]
      w.values(v)
    }
  }
  override def unsafeSet(ps: PreparedStatement, i: Int, a: A) = {
    var index = 0
    var n = i
    instances.foreach { case (w: Write[a]) =>
      val v = a.asInstanceOf[Product].productElement(index).asInstanceOf[a]
      w.unsafeSet(ps, n, v)

      index = index + 1
      n = n + w.length
    }
  }
  override def unsafeUpdate(rs: ResultSet, i: Int, a: A) = {
    var index = 0
    var n = i
    instances.foreach { case (w: Write[a]) =>
      val v = a.asInstanceOf[Product].productElement(index).asInstanceOf[a]
      w.unsafeUpdate(rs, n, v)

      index = index + 1
      n = n + w.length
    }
  }
}

trait WriteAutoPlatform extends WritePlatform {

  inline implicit def genWrite[A](using m: Mirror.ProductOf[A]): Write[A] = derived[A]
}
