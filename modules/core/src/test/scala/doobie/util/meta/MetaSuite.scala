// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util.meta

import doobie.H2DatabaseSpec
import doobie.syntax.string.*
import doobie.util.Get
import doobie.util.Put
import doobie.util.invariant.InvalidValue
import zio.test.assertCompletes
import zio.test.assertTrue

import scala.annotation.nowarn

@nowarn("msg=.*local method foo.*")
object MetaSuite extends H2DatabaseSpec {

  override val spec = suite("Meta")(
    test("exists for primitive types") {
      Meta[Int]: Unit
      Meta[String]: Unit
      assertCompletes
    },
    test("imply Get") {
      def foo[A: Meta] = Get[A]
      assertCompletes
    },
    test("imply Put") {
      def foo[A: Meta] = Put[A]
      assertCompletes
    },
    test("Meta.tiemap should accept valid values") {
      fr"select 'bar'".query[Foo].unique.transact.map { result =>
        assertTrue(result == Foo("bar"))
      }
    },
    test("Meta.tiemap should reject invalid values") {
      fr"select ''".query[Foo].unique.transact.either.map { result =>
        assertTrue(result == Left(InvalidValue[String, Foo]("", "may not be empty")))
      }
    },
  )

  case class Foo(str: String)
  object Foo {
    implicit val meta: Meta[Foo] = Meta[String].tiemap { s =>
      Either.cond(s.nonEmpty, Foo(s), "may not be empty")
    }(_.str)
  }

}
