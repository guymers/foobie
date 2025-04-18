// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

// relies on whenM, etc. so no cats for now
package example

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.show.*
import doobie.postgres.*
import doobie.postgres.free.largeobjectmanager.LargeObjectManagerIO
import doobie.syntax.connectionio.*
import doobie.util.transactor.Transactor

import java.io.File

/**
 * Example of using the high-level Large Object API. See the Postgres JDBC
 * driver doc and the source in doobie.contrib.postgresql for more information.
 */
object PostgresLargeObject extends IOApp.Simple {

  val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql:world",
    "postgres",
    "password",
  )

  val prog: LargeObjectManagerIO[Long] =
    for {
      oid <- PHLOM.createLOFromFile(1024, new File("world.sql"))
      _ <- PHLOM.createFileFromLO(1024, oid, new File("world2.sql"))
      _ <- PHLOM.delete(oid)
    } yield oid

  def run: IO[Unit] =
    PHC.pgGetLargeObjectAPI(prog).transact(xa).flatMap { oid =>
      IO(Console.println(show"oid was $oid"))
    }

}
