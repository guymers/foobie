// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.issue

import cats.Foldable
import cats.effect.IO
import cats.syntax.all.*
import doobie.free.connection.ConnectionIO
import doobie.syntax.connectionio.*
import doobie.syntax.string.*
import doobie.util.Write
import doobie.util.transactor.Transactor
import doobie.util.update.Update
import org.scalacheck.Prop.forAll

class `706` extends munit.ScalaCheckSuite {

  import cats.effect.unsafe.implicits.global

  val xa = Transactor.fromDriverManager[IO](
    "org.h2.Driver",
    "jdbc:h2:mem:issue-706;DB_CLOSE_DELAY=-1",
    "sa",
    "",
  )

  val setup: ConnectionIO[Unit] =
    sql"CREATE TABLE IF NOT EXISTS test (value INTEGER)".update.run.void

  def insert[F[_]: Foldable, A: Write](as: F[A]): ConnectionIO[Int] =
    Update[A]("INSERT INTO test VALUES (?)").updateMany(as)

  test("updateMany should work correctly for valid inputs") {
    forAll { (ns: List[Int]) =>
      val prog = setup *> insert(ns)
      assertEquals(prog.transact(xa).unsafeRunSync(), ns.length)
    }
  }

  // TODO: add a case for invalid inputs if we can find one that doesn't cause an
  // exception to be thrown.

}
