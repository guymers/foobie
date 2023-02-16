// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import doobie.implicits.*

class ManyRowsSuite extends munit.FunSuite {
  import PostgresTestTransactor.xa
  import cats.effect.unsafe.implicits.global

  test("select should take consistent memory") {
    val q = sql"""select a.name, b.name from city a, city b""".query[(String, String)]
    q.stream.take(5).transact(xa).compile.drain.unsafeRunSync()
  }

}
