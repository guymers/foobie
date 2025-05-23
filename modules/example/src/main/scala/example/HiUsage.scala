// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package example

import cats.Show
import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.show.*
import doobie.FC
import doobie.free.connection.ConnectionIO
import doobie.syntax.connectionio.*
import doobie.syntax.string.*
import doobie.util.Read
import doobie.util.transactor.Transactor
import fs2.Stream

// JDBC program using the high-level API
object HiUsage extends IOApp.Simple {

  // A very simple data type we will read
  final case class CountryCode(code: Option[String])
  object CountryCode {
    implicit val show: Show[CountryCode] = Show.fromToString

    implicit val read: Read[CountryCode] = Read.derived
  }

  // Program entry point
  def run: IO[Unit] = {
    val db = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      "jdbc:postgresql:world",
      "postgres",
      "password",
    )
    example.transact(db)
  }

  // An example action. Streams results to stdout
  lazy val example: ConnectionIO[Unit] =
    speakerQuery("English", 10).evalMap(c => FC.delay(println(show"~> $c"))).compile.drain

  // Construct an action to find countries where more than `pct` of the population speaks `lang`.
  // The result is a fs2.Stream that can be further manipulated by the caller.
  def speakerQuery(lang: String, pct: Double): Stream[ConnectionIO, CountryCode] =
    sql"SELECT COUNTRYCODE FROM COUNTRYLANGUAGE WHERE LANGUAGE = $lang AND PERCENTAGE > $pct".query[CountryCode].stream

}
