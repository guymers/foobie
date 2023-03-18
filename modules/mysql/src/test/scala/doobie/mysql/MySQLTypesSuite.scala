package doobie.mysql

import cats.syntax.foldable.*
import cats.syntax.show.*
import doobie.Fragment
import doobie.free.connection.ConnectionIO
import doobie.syntax.string.*
import doobie.util.Get
import doobie.util.Put
import doobie.util.Read
import doobie.util.Write
import doobie.util.transactor.Transactor
import zio.Task
import zio.ZIO
import zio.ZLayer
import zio.test.Gen
import zio.test.Live
import zio.test.TestAspect
import zio.test.assertCompletes
import zio.test.assertTrue
import zio.test.check

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

object MySQLTypesSuite extends MySQLDatabaseSpec {
  import doobie.mysql.util.generators.SQLGenerators.*
  import doobie.mysql.util.generators.TimeGenerators.*

  override val spec = suite("MySQLTypes")(
    suiteGetPut[java.time.OffsetDateTime](
      "timestamp(6)",
      genOffsetDateTime,
      skipNone = true, // returns the current timestamp, lol
      _.withOffsetSameInstant(ZoneOffset.UTC),
    ),
    suiteGetPut[java.time.Instant](
      "timestamp(6)",
      genInstant,
      skipNone = true, // returns the current timestamp, lol
    ),
    suiteGetPut[java.sql.Timestamp]("datetime(6)", genSQLTimestamp),
    suiteGetPut[LocalDateTime]("datetime(6)", genLocalDateTime),
    suiteGetPut[java.sql.Date]("date", genSQLDate),
    suiteGetPut[LocalDate]("date", genLocalDate),
    suiteGetPut[java.sql.Time]("time", genSQLTime),
    suiteGetPut[LocalTime]("time(6)", genLocalTime),
  )

  def suiteGetPut[A](
    columnType: String,
    gen: Gen[Any, A],
    skipNone: Boolean = false,
    expected: A => A = identity[A](_),
  )(implicit g: Get[A], p: Put[A]) = {
    suite(show"column ${columnType} as ${g.typeStack.toList.flatten.mkString_(".")}")(
      test("not null") {
        check(gen) { a =>
          insertNotNull(a).either.map { result =>
            assertTrue(result == Right(expected(a)))
          }
        }
      },
      test("nullable with value") {
        check(gen) { a =>
          insertNullable(Some(a)).either.map { result =>
            assertTrue(result == Right(Some(expected(a))))
          }
        }
      },
      test("nullable no value") {
        insertNullable(None).either.map { result =>
          assertTrue(result == Right(None))
        }
      } @@ (if (skipNone) TestAspect.ignore else TestAspect.identity),
    ).provideSomeLayerShared[Transactor[Task]](ZLayer.scoped(withTables(columnType)))
  }

  private def insertNotNull[A: Get: Put](a: A) = for {
    table <- ZIO.serviceWith[(Fragment, Fragment)](_._1)
    result <- insert(table, a).transact
  } yield result

  private def insertNullable[A: Get: Put](a: Option[A]) = for {
    table <- ZIO.serviceWith[(Fragment, Fragment)](_._2)
    result <- insert(table, a).transact
  } yield result

  private def insert[A: Read: Write](table: Fragment, a: A): ConnectionIO[A] = for {
    id <- fr"INSERT INTO $table (value) VALUES ($a)".update.withUniqueGeneratedKeys[Int]("id")
    a0 <- fr"SELECT value FROM $table WHERE id = $id".query[A].unique
  } yield a0

  private def withTables(columnType: String) = for {
    notNull <- withTable(columnType, nullable = false)
    nullable <- withTable(columnType, nullable = true)
  } yield (notNull, nullable)

  private def withTable(columnType: String, nullable: Boolean) = for {
    uuid <- Live.live(zio.Random.nextUUID)
    table = Fragment.const(show"test_types_${uuid.toString.replaceAll("-", "")}")
    create = fr"""CREATE TABLE $table(
      id INT NOT NULL AUTO_INCREMENT,
      value ${Fragment.const(columnType)} ${if (nullable) fr"" else fr"NOT NULL"},
      PRIMARY KEY (id)
    )""".update.run
    drop = fr"DROP TABLE IF EXISTS $table".update.run
    _ <- ZIO.acquireRelease(create.transact)(_ => drop.transact.ignoreLogged)
  } yield table

  def skip(columnType: String, msg: String = "not yet implemented") = {
    test(show"column $columnType ($msg)") {
      assertCompletes
    } @@ TestAspect.ignore
  }
}
