// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import java.sql.ResultSet
import scala.collection.immutable.ArraySeq
import scala.quoted.Expr
import scala.quoted.Quotes
import scala.quoted.Type
import scala.quoted.Varargs

trait ReadPlatform {

  inline def derived[A]: Read[A] = ${ ReadPlatformMacros.derivedImpl[A] }
}

object ReadPlatformMacros {

  def derivedImpl[A: Type](using q: Quotes): Expr[Read[A]] = {
    import q.reflect.*

    val tpe = TypeRepr.of[A]
    val symbol = tpe.typeSymbol
    if (!symbol.flags.is(Flags.Case)) {
      report.errorAndAbort(notCaseClassError[A])
    }

    val fields = symbol.caseFields

    val fieldReads = fields.map { symbol =>
      tpe.memberType(symbol).asType match {
        case '[t] =>
          Expr.summon[Read[t]].getOrElse(report.errorAndAbort(noReadInstanceForFieldError[t](symbol.name)))
      }
    }

    def constructor(values: Expr[Array[Any]]): Expr[A] = {
      val args = fields.zipWithIndex.map { case (symbol, index) =>
        tpe.memberType(symbol).asType match {
          case '[t] => '{ $values(${ Expr(index) }).asInstanceOf[t] }.asTerm
        }
      }

      Apply(Select(New(TypeTree.of[A]), symbol.primaryConstructor), args).asExprOf[A]
    }

    val allReads = Varargs(fieldReads.map(_.asExprOf[Read[?]]))

    '{ new ReadPlatformInstance[A](ArraySeq[Read[?]]($allReads*), values => ${ constructor('values) }) }
  }

  private def notCaseClassError[A: Type](using Quotes): String =
    s"Can only derive Read for case classes: ${Type.show[A]}"

  private def noReadInstanceForFieldError[A: Type](fieldName: String)(using Quotes): String =
    s"Could not find Read for field $fieldName: ${Type.show[A]}"
}

class ReadPlatformInstance[A](typeclasses: => ArraySeq[Read[?]], construct: Array[Any] => A) extends Read[A] {
  private lazy val instances = typeclasses
  override lazy val gets = instances.flatMap(_.gets)
  override def unsafeGet(rs: ResultSet, i: Int) = {
    val values = Read.build(instances)(rs, i)
    construct(values)
  }
}
