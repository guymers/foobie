// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.issue

import cats.Foldable
import cats.syntax.functor.*
import doobie.H2DatabaseSpec
import doobie.free.connection.ConnectionIO
import doobie.syntax.string.*
import doobie.util.Write
import doobie.util.fragment.Fragment
import doobie.util.update.Update
import zio.test.Gen
import zio.test.Live
import zio.test.assertTrue
import zio.test.check

object `706` extends H2DatabaseSpec {

  override val spec = suite("706")(
    test("updateMany should work correctly for valid inputs") {
      withTable.flatMap { table =>
        def insert[F[_]: Foldable, A: Write](as: F[A]): ConnectionIO[Int] =
          Update[A](fr"INSERT INTO $table VALUES (?)".update.sql).updateMany(as)

        check(Gen.listOfBounded(0, 10)(Gen.int)) { ns =>
          insert(ns).transact.map { result =>
            assertTrue(result == ns.length)
          }
        }
      }
    },
  )

  private def withTable = for {
    tableName <- Live.live(zio.Random.nextUUID).map(_.toString.replaceAll("-", ""))
    table = Fragment.const(s"test_$tableName")
    _ <- fr"CREATE TABLE IF NOT EXISTS $table (v INTEGER)".update.run.void.transact
      .withFinalizer(_ => fr"DROP TABLE IF NOT EXISTS $table".update.run.transact.ignoreLogged)
  } yield table

  // TODO: add a case for invalid inputs if we can find one that doesn't cause an
  // exception to be thrown.

}
