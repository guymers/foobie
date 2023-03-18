// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.H2DatabaseSpec
import doobie.syntax.string.*
import doobie.util.update.Update0
import zio.test.assertTrue

object UpdateSuite extends H2DatabaseSpec {

  override val spec = suite("Update")(
    suite("handles interpolated values")(
      test("put") {
        val someInt: Option[Int] = Some(2)
        val noneInt: Option[Int] = None

        val conn = for {
          _ <-
            Update0("CREATE LOCAL TEMPORARY TABLE IF NOT EXISTS test_update (v INT) NOT PERSISTENT", None).run
          a <- fr"INSERT INTO test_update VALUES (${1})".update.withUniqueGeneratedKeys[Option[Int]]("v")
          b <- fr"INSERT INTO test_update VALUES ($someInt)".update.withUniqueGeneratedKeys[Option[Int]]("v")
          c <- fr"INSERT INTO test_update VALUES ($noneInt)".update.withUniqueGeneratedKeys[Option[Int]]("v")
        } yield {
          assertTrue(a == Some(1)) &&
          assertTrue(b == someInt) &&
          assertTrue(c == noneInt)
        }
        conn.transact
      },
      test("write") {
        import doobie.util.Read.Auto.*
        import doobie.util.Write.Auto.*

        case class Id(v: Int)
        val someId: Option[Id] = Some(Id(2))
        val noneId: Option[Id] = None

        val conn = for {
          _ <-
            Update0("CREATE LOCAL TEMPORARY TABLE IF NOT EXISTS test_update (v INT) NOT PERSISTENT", None).run
          a <- fr"INSERT INTO test_update VALUES (${Id(1)})".update.withUniqueGeneratedKeys[Option[Id]]("v")
          b <- fr"INSERT INTO test_update VALUES ($someId)".update.withUniqueGeneratedKeys[Option[Id]]("v")
          c <- fr"INSERT INTO test_update VALUES ($noneId)".update.withUniqueGeneratedKeys[Option[Id]]("v")
        } yield {
          assertTrue(a == Some(Id(1))) &&
          assertTrue(b == someId) &&
          assertTrue(c == noneId)
        }
        conn.transact
      },
    ),
  )

}
