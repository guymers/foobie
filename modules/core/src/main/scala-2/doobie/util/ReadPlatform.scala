// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import magnolia1.CaseClass
import magnolia1.Magnolia

import java.sql.ResultSet
import scala.collection.immutable.ArraySeq
import scala.language.experimental.macros

trait ReadPlatform {

  type Typeclass[T] = Read[T]

  def join[T](ctx: CaseClass[Read, T]): Read[T] = {
    lazy val typeclasses = ctx.parameters.map(_.typeclass)

    new Read[T] {
      override val gets = typeclasses.to(ArraySeq).flatMap(_.gets)
      override def unsafeGet(rs: ResultSet, i: Int) = {
        val values = Read.build(typeclasses)(rs, i)
        ctx.rawConstruct(ArraySeq.unsafeWrapArray(values))
      }
    }
  }

  def derived[A]: Read[A] = macro Magnolia.gen[A]
}
