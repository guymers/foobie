// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.Applicative
import cats.effect.IO
import cats.kernel.Monoid
import cats.syntax.semigroup.*
import doobie.free.connection.ConnectionIO
import doobie.syntax.connectionio.*
import doobie.util.transactor.Transactor

class ConnectionIOSuite extends munit.FunSuite {

  import cats.effect.unsafe.implicits.global

  val xa = Transactor.fromDriverManager[IO](
    "org.h2.Driver",
    "jdbc:h2:mem:queryspec;DB_CLOSE_DELAY=-1",
    "sa",
    "",
  )

  test("Semigroup ConnectionIO") {
    val prg = Applicative[ConnectionIO].pure(List(1, 2, 3)) combine Applicative[ConnectionIO].pure(List(4, 5, 6))
    assertEquals(prg.transact(xa).unsafeRunSync(), List(1, 2, 3, 4, 5, 6))
  }

  test("Monoid ConnectionIO") {
    assertEquals(Monoid[ConnectionIO[List[Int]]].empty.transact(xa).unsafeRunSync(), Nil)
  }

}
