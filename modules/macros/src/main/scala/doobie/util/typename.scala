// Copyright (c) 2020-2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

object typename {
  final case class TypeName[A](value: String)
  object TypeName extends TypeNamePlatform

  def typeName[A](implicit ev: TypeName[A]): String = ev.value
}
