// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.syntax

import cats.syntax.apply.*
import doobie.syntax.string.*
import doobie.util.Write
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object StringSyntaxSuite extends ZIOSpecDefault {

  override val spec = suite("StringSyntax")(
    test("sql interpolator should support no-param queries") {
      val q = fr0"foo bar baz".query[Int]
      assertTrue(q.sql == "foo bar baz")
    },
    test("sql interpolator should support atomic types") {
      val a = 1
      val b = "two"
      val q = fr0"foo $a bar $b baz".query[Int]
      assertTrue(q.sql == "foo ? bar ? baz")
    },
    test("sql interpolator should handle leading params") {
      val a = 1
      val q = fr0"$a bar baz".query[Int]
      assertTrue(q.sql == "? bar baz")
    },
    test("sql interpolator should support trailing params") {
      val b = "two"
      val q = fr0"foo bar $b".query[Int]
      assertTrue(q.sql == "foo bar ?")
    },
    test("sql interpolator should support product params") {
      implicit val write: Write[(Int, String)] = (Write[Int], Write[String]).tupled

      val a = (1, "two")
      val q = fr0"foo bar $a".query[Int]
      assertTrue(q.sql == "foo bar ?,?")
    },
  )

}
