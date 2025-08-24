// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.effect.IO
import cats.effect.kernel.Resource
import doobie.H2DatabaseSpec
import doobie.free.connection.ConnectionIO
import doobie.stub.StubConnection
import doobie.syntax.string.*
import doobie.util.transactor.Transactor
import zio.ZIO
import zio.test.assertTrue

object StrategySuite extends H2DatabaseSpec {

  override val spec = suite("Strategy")(
    specTransactor("id", interpId),
    specTransactor("cats", interpCats),
  )

  private[doobie] def specTransactor(
    name: String,
    interp: ConnectionIO[Int] => ZIO[Any, Nothing, (StubConnection, Either[Throwable, Int])],
  ) = suite(name)(
    test("success") {
      interp(fr"select 1".query[Int].unique).map { case (c, result) =>
        assertTrue(result == Right(1)) &&
        assertTrue(
          !c.getAutoCommit &&
          c.numCommits == 1 &&
          c.numRollbacks == 0 &&
          c.isClosed,
        ) &&
        assertTrue(
          c.preparedStatements.size == 1 &&
          c.preparedStatements.forall(_.isClosed),
        ) &&
        assertTrue(
          c.preparedStatements.forall(_.resultSets.size == 1) &&
          c.preparedStatements.forall(_.resultSets.forall(_.isClosed)),
        )
      }
    },
    test("failure") {
      interp(fr"abc".query[Int].unique).map { case (c, result) =>
        assertTrue(result.isLeft) &&
        assertTrue(
          !c.getAutoCommit &&
          c.numCommits == 0 &&
          c.numRollbacks == 1 &&
          c.isClosed,
        ) &&
        assertTrue(
          c.preparedStatements.size == 1 &&
          c.preparedStatements.forall(_.isClosed),
        ) &&
        assertTrue(
          c.preparedStatements.forall(_.resultSets.size == 1) &&
          c.preparedStatements.forall(_.resultSets.forall(_.isClosed)),
        )
      }
    },
  )

  private def interpId[A](c: ConnectionIO[A]) = {
    val conn = new StubConnection()
    val transactor = Transactor.id(() => conn)
    ZIO.attempt(transactor.run(c)).either.map((conn, _))
  }

  private def interpCats[A](c: ConnectionIO[A]) = {
    val conn = new StubConnection()
    val create = Resource.fromAutoCloseable(IO.delay(conn))
    val transactor = Transactor.catsEffect((), create)

    import cats.effect.unsafe.implicits.global
    ZIO.fromFuture(_ => transactor.run(c).unsafeToFuture()).either.map((conn, _))
  }
}
