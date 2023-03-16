// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.H2DatabaseSpec
import doobie.free.connection.ConnectionIO
import doobie.syntax.string.*
import doobie.util.transactor.Transactor
import zio.Task
import zio.ZIO
import zio.test.assertTrue

object TransactorSuite extends H2DatabaseSpec {

  override val spec = suite("Transactor")(
    test("Connection.close should be called on success") {
      withTracker(fr"select 1".query[Int].unique).map { case (tracker, result) =>
        assertTrue(result == Right(1)) &&
        assertTrue(tracker.connections.map(_.isClosed) == List(true))
      }
    },
    test("Connection.close should be called on failure") {
      withTracker(fr"abc".query[Int].unique).map { case (tracker, result) =>
        assertTrue(result.isLeft) &&
        assertTrue(tracker.connections.map(_.isClosed) == List(true))
      }
    },
    test("[Streaming] Connection.close should be called on success") {
      withTracker(fr"select 1".query[Int].stream.compile.toList).map { case (tracker, result) =>
        assertTrue(result == Right(List(1))) &&
        assertTrue(tracker.connections.map(_.isClosed) == List(true))
      }
    },
    test("[Streaming] Connection.close should be called on failure") {
      withTracker(fr"abc".query[Int].stream.compile.toList).map { case (tracker, result) =>
        assertTrue(result.isLeft) &&
        assertTrue(tracker.connections.map(_.isClosed) == List(true))
      }
    },
  )

  private def withTracker[A](c: ConnectionIO[A]) = for {
    xa <- ZIO.service[Transactor[Task]]
    tracker = new ConnectionTracker
    transactor = tracker.track(xa)
    result <- transactor.trans(instance)(c).either
  } yield (tracker, result)
}

@SuppressWarnings(Array("org.wartremover.warts.Var"))
class ConnectionTracker {
  var connections = List.empty[java.sql.Connection]

  def track[F[_]](xa: Transactor[F]) = {
    def withA(t: Transactor[F]): Transactor.Aux[F, t.A] = {
      Transactor.connect.modify(
        t,
        f =>
          a => {
            f(a).map { conn =>
              connections = conn :: connections
              conn
            }
          },
      )
    }
    withA(xa)
  }
}
