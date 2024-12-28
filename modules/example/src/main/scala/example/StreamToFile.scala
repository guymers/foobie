// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package example

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.show.*
import doobie.syntax.stream.*
import doobie.syntax.string.*
import doobie.util.Read.Auto.*
import doobie.util.transactor.Transactor
import fs2.io.file.Files
import fs2.io.file.Path
import fs2.text.utf8

object StreamToFile extends IOApp.Simple {

  val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql:world",
    "postgres",
    "password",
  )

  def run: IO[Unit] =
    sql"select name, population from country"
      .query[(String, Int)]
      .stream
      .map { case (n, p) => show"$n, $p" }
      .intersperse("\n")
      .through(utf8.encode)
      .transact(xa)
      .through(Files[IO].writeAll(Path("/tmp/out.txt")))
      .compile
      .drain

}
