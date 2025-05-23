// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

// relies on streaming, so no cats for now
package example

import cats.effect.*
import cats.syntax.apply.*
import cats.syntax.functor.*
import cats.syntax.show.*
import doobie.HC
import doobie.free.connection.ConnectionIO
import doobie.postgres.*
import doobie.syntax.stream.*
import doobie.syntax.string.*
import doobie.util.transactor.Transactor
import fs2.Stream
import org.postgresql.*

import scala.concurrent.duration.*

/**
 * Example exposing PostgreSQL NOTIFY as a Process[ConnectionIO,
 * PGNotification]. This will likely be provided as a standard service in
 * doobie-postgres in a future version. To play with this program, run it and
 * then in another window do:
 *
 * > psql -d world -U postgres -c "notify foo, 'abc'"
 *
 * to send a notification. The program will exit after reading five
 * notifications.
 */
object PostgresNotify extends IOApp.Simple {

  /** A resource that listens on a channel and unlistens when we're done. */
  def channel(name: String): Resource[ConnectionIO, Unit] =
    Resource.make(PHC.pgListen(name) *> HC.commit)(_ => PHC.pgUnlisten(name) *> HC.commit)

  /**
   * Stream of PGNotifications on the specified channel, polling at the
   * specified rate. Note that this stream, when run, will commit the current
   * transaction.
   */
  def notificationStream(
    channelName: String,
    pollingInterval: FiniteDuration,
  ): Stream[IO, PGNotification] = {
    val stream = Stream.resource(channel(channelName)) *>
      Stream.eval(pgSleep(pollingInterval.toSeconds) *> PHC.pgGetNotifications <* HC.commit)
        .flatMap(Stream.emits(_))
    stream.transact(xa)
  }

  /** A transactor that knows how to connect to a PostgreSQL database. */
  val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql:world",
    "postgres",
    "password",
  )

  /**
   * Construct a stream of PGNotifications that prints to the console. Transform
   * it to a runnable process using the transcactor above, and run it.
   */
  def run: IO[Unit] =
    notificationStream("foo", 1.second)
      .map(n => show"${n.getPID} ${n.getName} ${n.getParameter}")
      .take(5)
      .evalMap(s => IO.delay(Console.println(s))).compile.drain

  private def pgSleep(seconds: Long) = fr"SELECT pg_sleep($seconds)".query[Unit].option.as(())
}
