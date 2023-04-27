package zoobie

import doobie.syntax.string.*
import zio.ZIO
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zoobie.stub.StubConnection

object TransactorSpec extends ZIOSpecDefault {

  override val spec = suite("Transactor")(
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
  )
}
