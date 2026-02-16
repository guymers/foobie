package zoobie

import cats.syntax.apply.*
import doobie.postgres.PostgresDatabaseSpec
import doobie.syntax.string.*
import zio.ZIO
import zio.durationInt
import zio.test.assertTrue

object TransactorIntegrationSpec extends PostgresDatabaseSpec {

  override val spec = suite("Transactor")(
    test("commits on success") {
      for {
        transactor <- ZIO.service[Transactor]

        clean = sql"DELETE FROM city WHERE id = 0".update.run
        _ <- transactor.run(clean)

        insert = sql"""
          INSERT INTO city
          (id, name, countrycode, district, population)
          VALUES
          (0, 'empty', 'ABC', '9', 1)
          ON CONFLICT DO NOTHING
        """.update.run
        _ <- transactor.run(insert)

        select = sql"SELECT name FROM city WHERE id = 0".query[String].option
        name <- transactor.run(select)

        _ <- transact(clean)
      } yield {
        assertTrue(name == Some("empty"))
      }
    },
    test("rollback on failure") {
      for {
        transactor <- ZIO.service[Transactor]
        select = sql"SELECT name FROM city WHERE id = -1".query[String].option
        exists1 <- transactor.run(select)

        insert = sql"""
          INSERT INTO city
          (id, name, countrycode, district, population)
          VALUES
          (-1, 'never', 'ABC', '9', 1)
        """.update.run
        invalid = sql"INVALID SQL".query.option
        result <- transactor.run(insert *> invalid).either

        exists2 <- transactor.run(select)
      } yield {
        assertTrue(exists1 == None) &&
        assertTrue(result.isLeft) &&
        assertTrue(exists2 == None)
      }
    },
    test("cancels query on interrupt") {
      for {
        transactor <- ZIO.service[Transactor]
        sleep = sql"SELECT pg_sleep(30)".update.run
        fiber <- transactor.run(sleep).fork
        _ <- ZIO.sleep(100.millis)
        result <- fiber.interrupt
      } yield {
        assertTrue(result.isInterrupted)
      }
    },
  )
}
