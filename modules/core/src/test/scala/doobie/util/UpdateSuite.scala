// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.H2DatabaseSpec
import doobie.syntax.string.*
import doobie.util.update.Update
import zio.test.assertTrue

object UpdateSuite extends H2DatabaseSpec {

  override val spec = suite("Update")(
    suite("handles interpolated values")(
      test("put") {
        val someInt: Option[Int] = Some(2)
        val noneInt: Option[Int] = None

        val conn = for {
          _ <- fr"CREATE LOCAL TEMPORARY TABLE IF NOT EXISTS test_update_put (v INT) NOT PERSISTENT".update.run
          a <- fr"INSERT INTO test_update_put VALUES (${1})".update.withUniqueGeneratedKeys[Option[Int]]("v")
          b <- fr"INSERT INTO test_update_put VALUES ($someInt)".update.withUniqueGeneratedKeys[Option[Int]]("v")
          c <- fr"INSERT INTO test_update_put VALUES ($noneInt)".update.withUniqueGeneratedKeys[Option[Int]]("v")
        } yield {
          assertTrue(a == Some(1)) &&
          assertTrue(b == someInt) &&
          assertTrue(c == noneInt)
        }
        conn.transact
      },
      test("write") {
        case class Id(v: Int)
        object Id {
          implicit val get: Get[Id] = Get[Int].map(apply(_))
          implicit val put: Put[Id] = Put[Int].contramap(_.v)
        }
        val someId: Option[Id] = Some(Id(2))
        val noneId: Option[Id] = None

        val conn = for {
          _ <- fr"CREATE LOCAL TEMPORARY TABLE IF NOT EXISTS test_update_write (v INT) NOT PERSISTENT".update.run
          a <- fr"INSERT INTO test_update_write VALUES (${Id(1)})".update.withUniqueGeneratedKeys[Option[Id]]("v")
          b <- fr"INSERT INTO test_update_write VALUES ($someId)".update.withUniqueGeneratedKeys[Option[Id]]("v")
          c <- fr"INSERT INTO test_update_write VALUES ($noneId)".update.withUniqueGeneratedKeys[Option[Id]]("v")
        } yield {
          assertTrue(a == Some(Id(1))) &&
          assertTrue(b == someId) &&
          assertTrue(c == noneId)
        }
        conn.transact
      },
    ),
    suite("many")(
      test("many") {
        val conn = for {
          _ <- fr"CREATE LOCAL TEMPORARY TABLE IF NOT EXISTS test_update_many (v INT) NOT PERSISTENT".update.run
          result <- Update[Int]("INSERT INTO test_update_many VALUES (?)").updateMany((1 to 10).toList)
        } yield {
          assertTrue(result == 10)
        }
        conn.transact
      },
      test("many returning returning generated keys") {
        val conn = for {
          _ <- fr"CREATE LOCAL TEMPORARY TABLE IF NOT EXISTS test_update_many_r (v INT) NOT PERSISTENT".update.run
          result <- Update[Int]("INSERT INTO test_update_many_r VALUES (?)")
            .updateManyReturningGeneratedKeys[Int]("v")((1 to 10).toList)
        } yield {
          assertTrue(result == (1 to 10).toList)
        }
        conn.transact
      },
    ),
  )

}
