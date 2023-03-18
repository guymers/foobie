// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import doobie.postgres.enums.*
import doobie.postgres.implicits.*
import doobie.syntax.string.*
import doobie.util.invariant.*
import doobie.util.meta.Meta
import zio.test.assertTrue

object ReadErrorSuite extends PostgresDatabaseSpec {

  implicit val MyEnumMetaOpt: Meta[MyEnum] = pgEnumStringOpt(
    "myenum",
    {
      case "foo" => Some(MyEnum.Foo)
      case "bar" => Some(MyEnum.Bar)
      case _ => None
    },
    {
      case MyEnum.Foo => "foo"
      case MyEnum.Bar => "bar"
    },
  )
  implicit val MyScalaEnumMeta: Meta[MyScalaEnum.Value] = pgEnum(MyScalaEnum, "myenum")
  implicit val MyJavaEnumMeta: Meta[MyJavaEnum] = pgJavaEnum[MyJavaEnum]("myenum")

  override val spec = suite("ReadError")(
    test("pgEnumStringOpt") {
      fr"select 'invalid'".query[MyEnum].unique.transact.either.map { result =>
        assertTrue(result == Left(InvalidEnum[MyEnum]("invalid")))
      }
    },
    test("pgEnum") {
      fr"select 'invalid' :: myenum".query[MyScalaEnum.Value].unique.transact.either.map { result =>
        assertTrue(result == Left(InvalidEnum[MyScalaEnum.Value]("invalid")))
      }
    },
    test("pgJavaEnum") {
      fr"select 'invalid' :: myenum".query[MyJavaEnum].unique.transact.either.map { result =>
        assertTrue(result == Left(InvalidEnum[MyJavaEnum]("invalid")))
      }
    },
  )

}
