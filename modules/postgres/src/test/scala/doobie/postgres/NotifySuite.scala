// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import cats.syntax.apply.*
import cats.syntax.show.*
import cats.syntax.traverse.*
import doobie.FC
import doobie.free.connection.ConnectionIO
import zio.Promise
import zio.ZIO
import zio.test.Live
import zio.test.assertTrue
import zoobie.Transactor

object NotifySuite extends PostgresDatabaseSpec {

  override val spec = suite("Notify")(
    test("LISTEN/NOTIFY should allow cross-connection notification") {
      for {
        channel <- randomChannelName
        result <- listen(channel, PHC.pgNotify(channel))
      } yield {
        assertTrue(result.length == 1)
      }
    },
    test("LISTEN/NOTIFY should allow cross-connection notification with parameter") {
      for {
        channel <- randomChannelName
        messages = List("foo", "bar", "baz", "qux")
        notify = messages.traverse(PHC.pgNotify(channel, _))
        result <- listen(channel, notify)
      } yield {
        assertTrue(result.map(_.getParameter) == messages)
      }
    },
    test("LISTEN/NOTIFY should collapse identical notifications") {
      for {
        channel <- randomChannelName
        messages = List("foo", "bar", "bar", "baz", "qux", "foo")
        notify = messages.traverse(PHC.pgNotify(channel, _))
        result <- listen(channel, notify)
      } yield {
        assertTrue(result.map(_.getParameter) == messages.distinct)
      }
    },
  )

  private def listen[A](channel: String, notify: ConnectionIO[A]) = for {
    transactor <- ZIO.service[Transactor]
    listenConfigured <- Promise.make[Nothing, Unit]
    notifySent <- Promise.make[Nothing, Unit]

    fiber <- ZIO.scoped[Any](for {
      conn <- transactor.connection
      run = transactor.translate(conn)
      _ <- run(FC.setAutoCommit(false) *> PHC.pgListen(channel) *> FC.commit)
      _ <- listenConfigured.succeed(())
      _ <- notifySent.await
      result <- run(PHC.pgGetNotifications)
    } yield result).fork

    _ <- listenConfigured.await
    _ <- notify.transact
    _ <- notifySent.succeed(())
    result <- fiber.join
  } yield result

  private def randomChannelName = Live.live(zio.Random.nextUUID).map { uuid =>
    show"cha_${uuid.toString.replaceAll("-", "")}"
  }
}
