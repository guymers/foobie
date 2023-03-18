// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.enumerated.JdbcType
import doobie.h2.H2DatabaseSpec
import doobie.syntax.string.*
import doobie.util.invariant.InvalidValue
import doobie.util.invariant.NonNullableColumnRead
import zio.test.assertCompletes
import zio.test.assertTrue

object GetSuite extends H2DatabaseSpec {

  override val spec = suite("Get")(
    test("exist for primitive types") {
      val _ = Get[Int]
      val _ = Get[String]
      assertCompletes
    },
    test("not allow map to observe null on the read side (AnyRef)") {
      fr"select null".query[Option[Foo]].unique.transact.map { result =>
        assertTrue(result == None)
      }
    },
    test("read non-null value (AnyRef)") {
      fr"select 'abc'".query[Foo].unique.transact.map { result =>
        assertTrue(result == Foo("ABC"))
      }
    },
    test("error when reading a NULL into an unlifted Scala type (AnyRef)") {
      fr"select null".query[Foo].unique.transact.either.map { result =>
        assertTrue(result == Left(NonNullableColumnRead(1, JdbcType.Char)))
      }
    },
    test("not allow map to observe null on the read side (AnyVal)") {
      fr"select null".query[Option[Bar]].unique.transact.map { result =>
        assertTrue(result == None)
      }
    },
    test("read non-null value (AnyVal)") {
      fr"select 1".query[Bar].unique.transact.map { result =>
        assertTrue(result == Bar(1))
      }
    },
    test("error when reading a NULL into an unlifted Scala type (AnyVal)") {
      fr"select null".query[Bar].unique.transact.either.map { result =>
        assertTrue(result == Left(NonNullableColumnRead(1, JdbcType.Integer)))
      }
    },
    test("error when reading an incorrect value") {
      fr"select 0".query[Bar].unique.transact.either.map { result =>
        assertTrue(result == Left(InvalidValue[Int, Bar](0, "cannot be 0")))
      }
    },
  )

  final case class Foo(s: String)
  final case class Bar(n: Int)

  // Both of these will fail at runtime if called with a null value, we check that this is
  // avoided below.
  implicit def FooMeta: Get[Foo] = Get[String].map(s => Foo(s.toUpperCase))
  implicit def barMeta: Get[Bar] = Get[Int].temap(n => if (n == 0) Left("cannot be 0") else Right(Bar(n)))

}
