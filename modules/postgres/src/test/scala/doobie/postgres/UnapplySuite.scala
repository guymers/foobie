// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import cats.syntax.all.*
import doobie.free.connection.ConnectionIO
import doobie.postgres.implicits.*

class UnapplySuite extends munit.FunSuite {

  test("Partial should allow use of sqlstate syntax") {
    1.pure[ConnectionIO].map(_ + 1).void: Unit
    1.pure[ConnectionIO].map(_ + 1).onPrivilegeNotRevoked(2.pure[ConnectionIO]): Unit
  }

}
