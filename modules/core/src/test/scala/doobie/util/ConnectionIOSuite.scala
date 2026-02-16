// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.Applicative
import cats.kernel.Monoid
import cats.syntax.applicative.*
import cats.syntax.semigroup.*
import doobie.H2DatabaseSpec
import doobie.free.connection.ConnectionIO
import doobie.free.connection.ConnectionIO.MonadErrorConnectionIO
import doobie.free.connection.ConnectionIO.MonoidConnectionIO
import zio.test.assertCompletes
import zio.test.assertTrue

object ConnectionIOSuite extends H2DatabaseSpec {

  override val spec = suite("ConnectionIO")(
    test("Semigroup") {
      val prg = Applicative[ConnectionIO].pure(List(1, 2, 3))
        .combine(Applicative[ConnectionIO].pure(List(4, 5, 6)))
      prg.transact.map { result =>
        assertTrue(result == List(1, 2, 3, 4, 5, 6))
      }
    },
    test("Monoid") {
      Monoid[ConnectionIO[List[Int]]].empty.transact.map { result =>
        assertTrue(result == Nil)
      }
    },
    test("ApplicativeError") {
      import doobie.syntax.applicativeerror.*

      val _ = 42.pure[ConnectionIO].attemptSql
      assertCompletes
    },
  )

}
