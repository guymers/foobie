// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.enums

import doobie.postgres.instances.enumeration.*
import doobie.util.meta.Meta

// create type myenum as enum ('foo', 'bar') <-- part of setup
sealed trait MyEnum
object MyEnum {
  case object Foo extends MyEnum
  case object Bar extends MyEnum

  implicit val MyEnumMeta: Meta[MyEnum] =
    pgEnumString(
      "myenum",
      {
        case "foo" => Foo
        case "bar" => Bar
      },
      {
        case Foo => "foo"
        case Bar => "bar"
      },
    )
}
