// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import cats.syntax.applicative.*
import cats.syntax.functor.*
import doobie.FC
import doobie.free.connection.ConnectionIO
import doobie.postgres.syntax.applicativeerror.*
import zio.test.ZIOSpecDefault
import zio.test.assertCompletes

object SyntaxSuite extends ZIOSpecDefault {

  override val spec = suite("Syntax")(
    test("Partial should allow use of sqlstate syntax") {
      val _ = 1.pure[ConnectionIO].map(_ + 1).void
      val _ = 1.pure[ConnectionIO].map(_ + 1).onPrivilegeNotRevoked(2.pure[ConnectionIO])
      assertCompletes
    },
    test("syntax should not overflow the stack on direct recursion") {
      def prog: ConnectionIO[Unit] = FC.delay(()).onUniqueViolation(prog)
      val _ = prog
      assertCompletes
    },
  )

}
