// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import magnolia1.Magnolia
import magnolia1.ReadOnlyCaseClass

import java.sql.PreparedStatement
import java.sql.ResultSet
import scala.collection.immutable.ArraySeq
import scala.language.experimental.macros

trait WritePlatform {

  type Typeclass[A] = Write[A]

  def join[A](ctx: ReadOnlyCaseClass[Write, A]): Write[A] = {
    lazy val typeclasses = ctx.parameters.map(_.typeclass)

    new Write[A] {
      override val puts = typeclasses.to(ArraySeq).flatMap(_.puts)
      override def values(a: A) = {
        ctx.parameters.flatMap(param => param.typeclass.values(param.dereference(a)))
      }
      @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
      override def unsafeSet(ps: PreparedStatement, i: Int, a: A) = {
        var n = i
        val it = ctx.parameters.iterator
        while (it.hasNext) {
          val param = it.next()
          param.typeclass.unsafeSet(ps, n, param.dereference(a))
          n = n + param.typeclass.length
        }
      }
      @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
      override def unsafeUpdate(rs: ResultSet, i: Int, a: A) = {
        var n = i
        val it = ctx.parameters.iterator
        while (it.hasNext) {
          val param = it.next()
          param.typeclass.unsafeUpdate(rs, n, param.dereference(a))
          n = n + param.typeclass.length
        }
      }
    }
  }

  def derived[A]: Write[A] = macro Magnolia.gen[A]
}

trait WriteAutoPlatform extends WritePlatform {

  implicit def genWrite[A]: Write[A] = macro Magnolia.gen[A]
}
