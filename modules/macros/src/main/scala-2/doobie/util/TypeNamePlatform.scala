// Copyright (c) 2020-2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.util.typename.TypeName

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

trait TypeNamePlatform {

  implicit def typeName[T]: TypeName[T] =
    macro TypeNamePlatform.typeName_impl[T]

}

object TypeNamePlatform {

  // https://stackoverflow.com/questions/15649720
  def typeName_impl[T](c: blackbox.Context): c.Expr[TypeName[T]] = {
    import c.universe.*
    val typeTree = c.macroApplication match {
      case TypeApply(_, List(typeTree)) => typeTree
      case _ => c.abort(c.enclosingPosition, "Type parameter is not a TypeApply")
    }
    c.Expr(q"doobie.util.typename.TypeName(${typeTree.toString})")
  }

}
