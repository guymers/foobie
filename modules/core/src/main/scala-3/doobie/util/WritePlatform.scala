// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import java.sql.PreparedStatement
import java.sql.ResultSet
import scala.collection.immutable.ArraySeq
import scala.quoted.Expr
import scala.quoted.Quotes
import scala.quoted.Type
import scala.quoted.Varargs

trait WritePlatform {

  inline def derived[A]: Write[A] = ${ WritePlatformMacros.derivedImpl[A] }
}

object WritePlatformMacros {

  def derivedImpl[A: Type](using q: Quotes): Expr[Write[A]] = {
    import q.reflect.*

    val tpe = TypeRepr.of[A]
    val symbol = tpe.typeSymbol
    if (!symbol.flags.is(Flags.Case)) {
      report.errorAndAbort(notCaseClassError[A])
    }

    val fields = symbol.caseFields

    def field[A0: Type, B: Type](a: Expr[A0], symbol: Symbol): Expr[B] =
      Select.unique(a.asTerm, symbol.name).asExprOf[B]

    val fieldInstances = fields.map { symbol =>
      tpe.memberType(symbol).asType match {
        case '[t] =>
          val writeExpr = Expr.summon[Write[t]].getOrElse(report.errorAndAbort(noWriteInstanceForFieldError[t](symbol.name)))
          '{
            new WritePlatformField.Instance[A, t]($writeExpr, (a: A) => ${ field[A, t]('a, symbol) })
          }
      }
    }

    val allFields = Varargs(fieldInstances.map(_.asExprOf[WritePlatformField[A]]))

    '{ new WritePlatformInstance[A](ArraySeq[WritePlatformField[A]]($allFields*)) }
  }

  private def notCaseClassError[A: Type](using Quotes): String =
    s"Can only derive Write for case classes: ${Type.show[A]}"

  private def noWriteInstanceForFieldError[A: Type](fieldName: String)(using Quotes): String =
    s"Could not find Write for field $fieldName: ${Type.show[A]}"
}

abstract class WritePlatformField[A] {
  type B
  def write: Write[B]
  def get(a: A): B
}
object WritePlatformField {

  class Instance[A, C](override val write: Write[C], get0: A => C) extends WritePlatformField[A] {
    override type B = C
    override def get(a: A): C = get0(a)
  }
}

class WritePlatformInstance[A](fields: => ArraySeq[WritePlatformField[A]]) extends Write[A] {
  private lazy val instances = fields
  override lazy val puts = instances.flatMap(_.write.puts)
  override def values(a: A) = instances.flatMap(field => field.write.values(field.get(a)))
  override def unsafeSet(ps: PreparedStatement, i: Int, a: A) = {
    var n = i
    instances.foreach { field =>
      field.write.unsafeSet(ps, n, field.get(a))
      n = n + field.write.length
    }
  }
  override def unsafeUpdate(rs: ResultSet, i: Int, a: A) = {
    var n = i
    instances.foreach { field =>
      field.write.unsafeUpdate(rs, n, field.get(a))
      n = n + field.write.length
    }
  }
}
