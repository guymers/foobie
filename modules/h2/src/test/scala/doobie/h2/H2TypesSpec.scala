// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.h2

import cats.syntax.foldable.*
import cats.syntax.show.*
import doobie.FC
import doobie.Fragment
import doobie.Update0
import doobie.free.connection.ConnectionIO
import doobie.h2.implicits.*
import doobie.syntax.string.*
import doobie.util.Get
import doobie.util.Put
import doobie.util.update.Update
import zio.test.Gen
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
          putGet(columnType, a).transact.either.map { result =>
            assertTrue(result == Right(a))
          }
        }
      },
      test("nullable with value") {
        check(gen) { a =>
          putGetOpt(columnType, Some(a)).transact.either.map { result =>
            assertTrue(result == Right(Some(a)))
          }
        }
      },
      test("nullable no value") {
        putGetOpt(columnType, None).transact.either.map { result =>
          assertTrue(result == Right(None))
        }
      },
    )
  }

  private def putGet[A: Get: Put](columnType: String, a: A): ConnectionIO[A] = for {
    table <- FC.delay(s"test_${columnType.replaceAll("[^a-z]", "_")}")
    _ <- Update0(s"CREATE LOCAL TEMPORARY TABLE $table (v $columnType NOT NULL)", None).run
    _ <- Update[A](s"INSERT INTO $table VALUES (?)", None).run(a)
    a0 <- fr"SELECT v FROM ${Fragment.const(table)}".query[A].unique
  } yield a0

  private def putGetOpt[A: Get: Put](columnType: String, a: Option[A]): ConnectionIO[Option[A]] = for {
    table <- FC.delay(s"test_${columnType.replaceAll("[^a-z]", "_")}")
    _ <- Update0(s"CREATE LOCAL TEMPORARY TABLE $table (v $columnType)", None).run
    _ <- Update[Option[A]](s"INSERT INTO $table VALUES (?)", None).run(a)
    a0 <- fr"SELECT v FROM ${Fragment.const(table)}".query[Option[A]].unique
  } yield a0

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
