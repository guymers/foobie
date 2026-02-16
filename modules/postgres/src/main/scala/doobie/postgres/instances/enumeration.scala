// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.instances

import cats.data.NonEmptyList
import doobie.enumerated.JdbcType
import doobie.util.invariant.*
import doobie.util.meta.Meta
import doobie.util.typename.*
import org.postgresql.util.*

import scala.reflect.ClassTag

object enumeration {

  private def enumPartialMeta(name: String): Meta[String] =
    Meta.Basic.many[String](
      NonEmptyList.of(JdbcType.Other, JdbcType.VarChar), // https://github.com/tpolecat/doobie/issues/303
      NonEmptyList.of(JdbcType.Other, JdbcType.VarChar),
      Nil,
      (rs, n) => rs.getString(n),
      (ps, n, a) => {
        val o = new PGobject
        o.setValue(a)
        o.setType(name)
        ps.setObject(n, o)
      },
      (rs, n, a) => {
        val o = new PGobject
        o.setValue(a)
        o.setType(name)
        rs.updateObject(n, o)
      },
    )

  /**
   * Construct a `Meta` for values of the given type, mapped via `String` to the
   * named PostgreSQL enum type.
   */
  def pgEnumString[A: TypeName](name: String, f: String => A, g: A => String): Meta[A] =
    enumPartialMeta(name).timap[A](f)(g)

  /**
   * Construct a `Meta` for values of the given type, mapped via `String` to the
   * named PostgreSQL enum type with tranparent partiality.
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def pgEnumStringOpt[A: TypeName](name: String, f: String => Option[A], g: A => String): Meta[A] =
    pgEnumString(name, { (s: String) => f(s).getOrElse(throw doobie.util.invariant.InvalidEnum[A](s)) }, g)

  /**
   * Construct a `Meta` for value members of the given `Enumeration`.
   */
  @SuppressWarnings(Array(
    "org.wartremover.warts.Enumeration",
    "org.wartremover.warts.Throw",
    "org.wartremover.warts.ToString",
  ))
  def pgEnum(e: Enumeration, name: String): Meta[e.Value] =
    pgEnumString[e.Value](
      name,
      a =>
        try e.withName(a)
        catch {
          case _: NoSuchElementException => throw InvalidEnum[e.Value](a)
        },
      _.toString,
    )

  /**
   * Construct a `Meta` for value members of the given Java `enum`.
   */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Throw"))
  def pgJavaEnum[E <: java.lang.Enum[E]: TypeName](name: String)(implicit E: ClassTag[E]): Meta[E] = {
    val clazz = E.runtimeClass.asInstanceOf[Class[E]]
    pgEnumString[E](
      name,
      a =>
        try java.lang.Enum.valueOf(clazz, a)
        catch {
          case _: IllegalArgumentException => throw InvalidEnum[E](a)
        },
      _.name,
    )
  }
}
