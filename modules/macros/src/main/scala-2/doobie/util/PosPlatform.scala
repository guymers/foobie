// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.util.pos.Pos

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

trait PosPlatform {

  /** A `Pos` can be forged on demand. */
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  implicit def instance: Pos = macro PosPlatform.PosMacros.instance_impl

}

object PosPlatform {

  class PosMacros(val c: blackbox.Context) {
    import c.universe.*
    def instance_impl: Tree = {
      val file = c.enclosingPosition.source.path
      val line = c.enclosingPosition.line
      q"_root_.doobie.util.pos.Pos($file, $line)"
    }
  }

}
