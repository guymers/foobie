// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.syntax

import cats.syntax.foldable.*
import doobie.syntax.put.*
import doobie.syntax.string.*
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object PutSyntaxSuite extends ZIOSpecDefault {

  override val spec = suite("PutSyntax")(
    test("convert to a fragment") {
      assertTrue((fr"SELECT" ++ 1.fr).query[Unit].sql == "SELECT ? ")
    },
    test("convert to a fragment0") {
      assertTrue((fr"SELECT" ++ 1.fr0).query[Unit].sql == "SELECT ?")
    },
    test("convert an option to a fragment") {
      assertTrue((fr"SELECT" ++ Some(1).fr).query[Unit].sql == "SELECT ? ")
    },
    test("convert an option to a fragment0") {
      assertTrue((fr"SELECT" ++ Some(1).fr0).query[Unit].sql == "SELECT ?")
    },
    test("work in a map") {
      assertTrue(List(1, 2, 3).foldMap(_.fr).query[Unit].sql == "? ? ? ")
    },
    test("work in a map with fr0") {
      assertTrue(List(1, 2, 3).foldMap(_.fr0).query[Unit].sql == "???")
    },
  )

}
