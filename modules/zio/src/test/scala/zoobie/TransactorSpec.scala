package zoobie

import doobie.FC
import doobie.syntax.string.*
import fs2.Stream
import zio.Chunk
import zio.Ref
import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zoobie.stub.StubConnection

object TransactorSpec extends ZIOSpecDefault {

  override val spec = suite("Transactor")(
    suite("stream")(
      test("acquires and releases a connection for each effect") {
        for {
          acquisitions <- Ref.make(Vector.empty[(StubConnection, Boolean)])
          connection = ZIO.acquireRelease(acquisitions.modify { previous =>
            val c = new StubConnection(_ => true)
            (c, previous :+ ((c, previous.forall(_._1.isClosed))))
          })(c => ZIO.succeed(c.close()))
          tx = Transactor(connection, Transactor.interpreter, Transactor.strategies.transactional)
          source = Stream.eval(FC.raw(identity)) ++ Stream.eval(FC.raw(identity)) ++ Stream.eval(FC.raw(identity))
          rows <- tx.stream(source).runCollect
          acquired <- acquisitions.get
        } yield {
          assertTrue(acquired.size == 3) &&
          assertTrue(rows.toVector == acquired.map(_._1)) &&
          assertTrue(acquired.forall { case (c, previousReleased) => previousReleased && c.isClosed && c.numCommits == 1 && c.numRollbacks == 0 })
        }
      },
      test("commits earlier effects and rolls back only the failing effect") {
        val c = new StubConnection(_ => true)
        val tx = Transactor(ZIO.succeed(c), Transactor.interpreter, Transactor.strategies.transactional)
        val source = Stream.eval(FC.pure(1)) ++ Stream.eval(FC.raiseError[Int](new java.sql.SQLException("boom")))
        tx.stream(source).runCollect.exit.map { result =>
          assertTrue(result.isFailure) &&
          assertTrue(c.numCommits == 1) &&
          assertTrue(c.numRollbacks == 1)
        }
      },
    ),
    suite("streamSingleConnection")(
      test("commits once after the entire stream") {
        val c = new StubConnection(_ => true)
        val tx = Transactor(ZIO.succeed(c), Transactor.interpreter, Transactor.strategies.transactional)
        val source = Stream.eval(FC.pure(1)) ++ Stream.eval(FC.pure(2)) ++ Stream.eval(FC.pure(3))
        tx.streamSingleConnection(source).runCollect.map { rows =>
          assertTrue(rows == Chunk(1, 2, 3)) &&
          assertTrue(c.numCommits == 1) &&
          assertTrue(c.numRollbacks == 0)
        }
      },
      test("rolls back on failure") {
        val c = new StubConnection(_ => true)
        val tx = Transactor(ZIO.succeed(c), Transactor.interpreter, Transactor.strategies.transactional)
        val source = Stream.eval(FC.pure(1)) ++ Stream.eval(FC.raiseError[Int](new java.sql.SQLException("boom")))
        tx.streamSingleConnection(source).runCollect.exit.map { result =>
          assertTrue(result.isFailure) &&
          assertTrue(c.numCommits == 0) &&
          assertTrue(c.numRollbacks == 1)
        }
      },
      test("rolls back when consumption stops early") {
        val c = new StubConnection(_ => true)
        val tx = Transactor(ZIO.succeed(c), Transactor.interpreter, Transactor.strategies.transactional)
        tx.streamSingleConnection(Stream.repeatEval(FC.pure(1))).take(1).runCollect.map { rows =>
          assertTrue(rows == Chunk(1)) &&
          assertTrue(c.numCommits == 0) &&
          assertTrue(c.numRollbacks == 1)
        }
      },
    ),
    suite("transactional")(
      test("commits on success") {
        for {
          _ <- ZIO.unit
          connection = new StubConnection(_ => true)
          transactor = Transactor(ZIO.succeed(connection), Transactor.interpreter, Transactor.strategies.transactional)
          query = sql"SELECT 1".query.option
          _ <- transactor.run(query)
        } yield {
          assertTrue(connection.numCommits == 1) &&
          assertTrue(connection.numRollbacks == 0)
        }
      },
      test("rollback on failure") {
        for {
          _ <- ZIO.unit
          connection = new StubConnection(_ => true)
          transactor = Transactor(ZIO.succeed(connection), Transactor.interpreter, Transactor.strategies.transactional)
          query = sql"INVALID SQL".query.option
          _ <- transactor.run(query).either
        } yield {
          assertTrue(connection.numCommits == 0) &&
          assertTrue(connection.numRollbacks == 1)
        }
      },
    ),
  ) @@ TestAspect.timeout(15.seconds)
}
