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

  inline def summonAll[T <: Tuple]: List[Write[?]] = {
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (t *: ts) => summonInline[Write[t]] :: summonAll[ts]
    }
  }

  inline def derived[A](using m: Mirror.ProductOf[A]): Write[A] = {
    lazy val typeclasses = summonAll[m.MirroredElemTypes]

    new Write[A] {
      override val puts = typeclasses.to(ArraySeq).flatMap(_.puts)
      override def values(a: A) = {
        typeclasses.zipWithIndex.flatMap { case (typeclass: Write[a], i) =>
          val v = a.asInstanceOf[Product].productElement(i).asInstanceOf[a]
          typeclass.values(v)
        }
      }
      override def unsafeSet(ps: PreparedStatement, i: Int, a: A) = {
        var index = 0
        var n = i
        typeclasses.foreach { case (typeclass: Write[a]) =>
          val v = a.asInstanceOf[Product].productElement(index).asInstanceOf[a]
          typeclass.unsafeSet(ps, n, v)

          index = index + 1
          n = n + typeclass.length
        }
      }
      override def unsafeUpdate(rs: ResultSet, i: Int, a: A) = {
        var index = 0
        var n = i
        typeclasses.foreach { case (typeclass: Write[a]) =>
          val v = a.asInstanceOf[Product].productElement(index).asInstanceOf[a]
          typeclass.unsafeUpdate(rs, n, v)

          index = index + 1
          n = n + typeclass.length
        }
      }
    }
  }
}

trait WriteAutoPlatform extends WritePlatform {

  inline implicit def genWrite[A](using m: Mirror.ProductOf[A]): Write[A] = derived[A]
}
