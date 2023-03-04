// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

// relies on streaming, so no cats for now
package example

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.IOLocal
import doobie.KleisliInterpreter
import doobie.syntax.all.*
import doobie.util.transactor.Transactor

import java.sql.Connection

object LoggingExample extends IOApp.Simple {

  case class User(name: String)

  private val db = Transactor.fromDriverManager[IO](
    "org.h2.Driver",
    "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
    "sa",
    "",
  )

  def run: IO[Unit] = {
    val currentUser = IOLocal[Option[User]](None).unsafeRunSync()(runtime)

    def logSQL[T](sql: String, result: Kleisli[IO, Connection, T]): Kleisli[IO, Connection, T] = {
      result.tapWithF { case (_, t) =>
        for {
          user <- currentUser.get
          _ <- IO.delay {
            println(s"user $user; sql: '$sql'")
          }
        } yield t
      }
    }

    val i = KleisliInterpreter[IO]
    val LoggingConnectionInterpreter = new i.ConnectionInterpreter {
      override def prepareStatement(a: String) = logSQL(a, super.prepareStatement(a))
    }

    val loggingDb = db.copy(interpret0 = LoggingConnectionInterpreter)

    for {
      _ <- currentUser.set(Some(User("a")))
      _ <- fr"SELECT 1".query[Int].unique.transact(loggingDb)

      _ <- currentUser.set(Some(User("b")))
      _ <- fr"SELECT 2".query[Int].unique.transact(loggingDb)
    } yield ()
  }

}
