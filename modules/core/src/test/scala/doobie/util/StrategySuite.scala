// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.syntax.apply.*
import doobie.H2DatabaseSpec
import doobie.WeakAsync
import doobie.free.KleisliInterpreter
import doobie.free.connection.ConnectionIO
import doobie.syntax.string.*
import doobie.util.transactor.Transactor
import zio.Task
import zio.ZIO
import zio.test.assertTrue

object StrategySuite extends H2DatabaseSpec {

  override val spec = suite("Strategy")(
    test("Connection.autoCommit should be set to false") {
      interp(fr"select 1".query[Int].unique).map { case (i, result) =>
        val autoCommit = i.Connection.autoCommit
        assertTrue(result == Right(1)) && assertTrue(autoCommit == Some(false))
      }
    },
    test("Connection.commit should be called on success") {
      interp(fr"select 1".query[Int].unique).map { case (i, result) =>
        val commit = i.Connection.commit
        assertTrue(result == Right(1)) && assertTrue(commit == Some(()))
      }
    },
    test("Connection.commit should NOT be called on failure") {
      interp(fr"abc".query[Int].unique).map { case (i, result) =>
        val commit = i.Connection.commit
        assertTrue(result.isLeft) && assertTrue(commit == None)
      }
    },
    test("Connection.rollback should NOT be called on success") {
      interp(fr"select 1".query[Int].unique).map { case (i, result) =>
        val rollback = i.Connection.rollback
        assertTrue(result == Right(1)) && assertTrue(rollback == None)
      }
    },
    test("Connection.rollback should be called on failure") {
      interp(fr"abc".query[Int].unique).map { case (i, result) =>
        val rollback = i.Connection.rollback
        assertTrue(result.isLeft) && assertTrue(rollback == Some(()))
      }
    },
    test("[Streaming] Connection.autoCommit should be set to false") {
      interp(fr"select 1".query[Int].stream.compile.toList).map { case (i, result) =>
        val autoCommit = i.Connection.autoCommit
        assertTrue(result == Right(List(1))) && assertTrue(autoCommit == Some(false))
      }
    },
    test("[Streaming] Connection.commit should be called on success") {
      interp(fr"select 1".query[Int].stream.compile.toList).map { case (i, result) =>
        val commit = i.Connection.commit
        assertTrue(result == Right(List(1))) && assertTrue(commit == Some(()))
      }
    },
    test("[Streaming] Connection.commit should NOT be called on failure") {
      interp(fr"abc".query[Int].stream.compile.toList).map { case (i, result) =>
        val commit = i.Connection.commit
        assertTrue(result.isLeft) && assertTrue(commit == None)
      }
    },
    test("[Streaming] Connection.rollback should NOT be called on success") {
      interp(fr"select 1".query[Int].stream.compile.toList).map { case (i, result) =>
        val rollback = i.Connection.rollback
        assertTrue(result == Right(List(1))) && assertTrue(rollback == None)
      }
    },
    test("[Streaming] Connection.rollback should be called on failure") {
      interp(fr"abc".query[Int].stream.compile.toList).map { case (i, result) =>
        val rollback = i.Connection.rollback
        assertTrue(result.isLeft) && assertTrue(rollback == Some(()))
      }
    },
    test("PreparedStatement.close should be called on success") {
      interp(fr"select 1".query[Int].unique).map { case (i, result) =>
        val close = i.PreparedStatement.close
        assertTrue(result == Right(1)) && assertTrue(close == Some(()))
      }
    },
    test("PreparedStatement.close should be called on failure") {
      interp(fr"select 'x'".query[Int].unique).map { case (i, result) =>
        val close = i.PreparedStatement.close
        assertTrue(result.isLeft) && assertTrue(close == Some(()))
      }
    },
    test("[Streaming] PreparedStatement.close should be called on success") {
      interp(fr"select 1".query[Int].stream.compile.toList).map { case (i, result) =>
        val close = i.PreparedStatement.close
        assertTrue(result == Right(List(1))) && assertTrue(close == Some(()))
      }
    },
    test("[Streaming] PreparedStatement.close should be called on failure") {
      interp(fr"select 'x'".query[Int].stream.compile.toList).map { case (i, result) =>
        val close = i.PreparedStatement.close
        assertTrue(result.isLeft) && assertTrue(close == Some(()))
      }
    },
    test("ResultSet.close should be called on success") {
      interp(fr"select 1".query[Int].unique).map { case (i, result) =>
        val close = i.ResultSet.close
        assertTrue(result == Right(1)) && assertTrue(close == Some(()))
      }
    },
    test("ResultSet.close should be called on failure") {
      interp(fr"select 'x'".query[Int].unique).map { case (i, result) =>
        val close = i.ResultSet.close
        assertTrue(result.isLeft) && assertTrue(close == Some(()))
      }
    },
    test("[Streaming] ResultSet.close should be called on success") {
      interp(fr"select 1".query[Int].stream.compile.toList).map { case (i, result) =>
        val close = i.ResultSet.close
        assertTrue(result == Right(List(1))) && assertTrue(close == Some(()))
      }
    },
    test("[Streaming] ResultSet.close should be called on failure") {
      interp(fr"select 'x'".query[Int].stream.compile.toList).map { case (i, result) =>
        val close = i.ResultSet.close
        assertTrue(result.isLeft) && assertTrue(close == Some(()))
      }
    },
  )

  private def interp[A](c: ConnectionIO[A]): ZIO[Transactor[Task], Nothing, (Interp, Either[Throwable, A])] = for {
    transactor <- ZIO.service[Transactor[Task]]
    i = new Interp
    xa = Transactor.interpret[Task].set(transactor, i.ConnectionInterpreter)
    result <- xa.trans(instance)(c).either
  } yield {
    (i, result)
  }
}

// an instrumented interpreter
@SuppressWarnings(Array("org.wartremover.warts.Var"))
class Interp extends KleisliInterpreter[Task]()(
    WeakAsync.doobieWeakAsyncForAsync(zio.interop.catz.asyncInstance[Any]),
  ) {

  object Connection {
    var autoCommit: Option[Boolean] = None
    var close: Option[Unit] = None
    var commit: Option[Unit] = None
    var rollback: Option[Unit] = None
  }

  object PreparedStatement {
    var close: Option[Unit] = None
  }

  object ResultSet {
    var close: Option[Unit] = None
  }

  override lazy val ConnectionInterpreter = new ConnectionInterpreter {
    override val close = delay(Connection.close = Some(())) *> super.close
    override val rollback = delay(Connection.rollback = Some(())) *> super.rollback
    override val commit = delay(Connection.commit = Some(())) *> super.commit
    override def setAutoCommit(b: Boolean) = delay(Connection.autoCommit = Option(b)) *> super.setAutoCommit(b)
  }

  override lazy val PreparedStatementInterpreter = new PreparedStatementInterpreter {
    override val close = delay(PreparedStatement.close = Some(())) *> super.close
  }

  override lazy val ResultSetInterpreter = new ResultSetInterpreter {
    override val close = delay(ResultSet.close = Some(())) *> super.close
  }

}
