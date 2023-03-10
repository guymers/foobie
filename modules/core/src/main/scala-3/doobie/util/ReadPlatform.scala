// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import java.sql.ResultSet
import scala.collection.immutable.ArraySeq
import scala.compiletime.erasedValue
import scala.compiletime.summonInline
import scala.deriving.Mirror

trait ReadPlatform {

  inline def summonAll[T <: Tuple]: List[Read[?]] = {
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (t *: ts) => summonInline[Read[t]] :: summonAll[ts]
    }
  }

  inline def derived[A](using m: Mirror.ProductOf[A]): Read[A] = {
    lazy val typeclasses = summonAll[m.MirroredElemTypes].to(ArraySeq)

    new Read[A] {
      override val gets = typeclasses.to(ArraySeq).flatMap(_.gets)
      override def unsafeGet(rs: ResultSet, i: Int) = {
        val values = Read.build(typeclasses)(rs, i)
        m.fromProduct(Tuple.fromArray(values))
      }
    }
  }
}

trait ReadAutoPlatform extends ReadPlatform {

  inline implicit def genRead[A](using m: Mirror.ProductOf[A]): Read[A] = derived[A]
}
