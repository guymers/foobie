// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.h2

import cats.syntax.foldable.*
import cats.syntax.show.*
import doobie.free.connection.ConnectionIO
import doobie.h2.implicits.*
import doobie.syntax.string.*
import doobie.util.Get
import doobie.util.Put
import doobie.util.Read
import doobie.util.Write
import doobie.util.fragment.Fragment
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

import java.nio.charset.StandardCharsets
import scala.annotation.nowarn

// Establish that we can read various types. It's not very comprehensive as a test, bit it's a start.
object H2TypesSpec extends H2DatabaseSpec {

  // a faster way of generating strings
  private val genString = Gen.bounded(0, 2048)(i => Gen.fromRandom(_.nextString(i)))

  override val spec = suite("H2Types")(
    suiteGetPut("BOOLEAN", Gen.boolean),
    suiteGetPut("TINYINT", Gen.byte),
    suiteGetPut("SMALLINT", Gen.short),
    suiteGetPut("INT", Gen.int),
    suiteGetPut("BIGINT", Gen.long),
    suiteGetPut("REAL", Gen.float),
    suiteGetPut("DOUBLE PRECISION", Gen.double),
    suiteGetPut("DECFLOAT", Gen.bigDecimal(Double.MinValue, Double.MaxValue)),
    suiteGetPut("TIME", genSQLTime),
    suiteGetPut("TIME(9)", Gen.localTime),
    suiteGetPut("DATE", genSQLDate),
    suiteGetPut("DATE", Gen.localDate),
    suiteGetPut("TIMESTAMP(9)", genSQLTimestamp),
    suiteGetPut("TIMESTAMP(9)", Gen.localDateTime),
    suiteGetPut("TIME(9) WITH TIME ZONE", Gen.offsetTime),
    suiteGetPut("TIMESTAMP(9) WITH TIME ZONE", Gen.offsetDateTime),
    suiteGetPut("TIMESTAMP(9) WITH TIME ZONE", Gen.instant),
    suiteGetPut("VARCHAR", Gen.zoneId),
    suiteGetPut("BINARY VARYING", genString.map(_.getBytes(StandardCharsets.UTF_8).toList)),
    skip("OTHER"),
    suiteGetPut("VARCHAR", genString),
    suiteGetPut("CHAR(3)", Gen.alphaNumericStringBounded(0, 3).map(_.padTo(3, ' '))),
    skip("BLOB"),
    skip("CLOB"),
    suiteGetPut("UUID", Gen.uuid),
    suiteGetPut("INT ARRAY", Gen.listOfBounded(0, 10)(Gen.int)),
    suiteGetPut("VARCHAR ARRAY", Gen.listOfBounded(0, 10)(genString)),
    skip("GEOMETRY"),
  )

  def suiteGetPut[A](columnType: String, gen: Gen[Any, A])(implicit g: Get[A], p: Put[A]) = {
    suite(show"column ${columnType} as ${g.typeStack.toList.flatten.mkString_(".")}")(
      test("not null") {
        check(gen) { a =>
          insertNotNull(a).either.map { result =>
            assertTrue(result == Right(a))
          }
        }
      },
      test("nullable with value") {
        check(gen) { a =>
          insertNullable(Some(a)).either.map { result =>
            assertTrue(result == Right(Some(a)))
          }
        }
      },
      test("nullable no value") {
        insertNullable(None).either.map { result =>
          assertTrue(result == Right(None))
        }
      },
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

  private def insert[A: Read: Write](table: Fragment, a: A): ConnectionIO[A] = {
    fr"INSERT INTO $table (v) VALUES ($a)".update.withUniqueGeneratedKeys[A]("v")
  }

  private def withTables(columnType: String) = for {
    notNull <- withTable(columnType, nullable = false)
    nullable <- withTable(columnType, nullable = true)
  } yield (notNull, nullable)

  private def withTable(columnType: String, nullable: Boolean) = for {
    uuid <- Live.live(zio.Random.nextUUID)
    table = Fragment.const(show"test_types_${uuid.toString.replaceAll("-", "")}")
    col = Fragment.const(columnType)
    create = fr"CREATE MEMORY TABLE $table (v $col ${if (nullable) fr"" else fr"NOT NULL"}) NOT PERSISTENT".update.run
    drop = fr"DROP TABLE IF EXISTS $table".update.run
    _ <- ZIO.acquireRelease(create.transact)(_ => drop.transact.ignoreLogged)
  } yield table

  private def skip(col: String, msg: String = "not yet implemented") = {
    test(show"Mapping for $col - $msg") {
      assertCompletes
    } @@ TestAspect.ignore
  }

  @nowarn("cat=deprecation")
  private lazy val genSQLTime = for {
    h <- Gen.int(0, 23)
    m <- Gen.int(0, 59)
    s <- Gen.int(0, 59)
  } yield new java.sql.Time(h, m, s)

  @nowarn("cat=deprecation")
  private lazy val genSQLDate = for {
    y <- Gen.int(0, 8099)
    m <- Gen.int(0, 11)
    d <- Gen.int(1, 31)
  } yield new java.sql.Date(y, m, d)

  @nowarn("cat=deprecation")
  private lazy val genSQLTimestamp = for {
    d <- genSQLDate
    t <- genSQLTime
    n <- Gen.int(0, 999999999)
  } yield new java.sql.Timestamp(d.getYear, d.getMonth, d.getDate, t.getHours, t.getMinutes, t.getSeconds, n)

}
