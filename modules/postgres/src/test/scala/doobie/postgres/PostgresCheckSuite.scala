// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import doobie.postgres.enums.*
import doobie.syntax.string.*
import doobie.util.Get
import doobie.util.Put
import doobie.util.analysis.ColumnTypeError
import doobie.util.analysis.ParameterTypeError
import doobie.util.fragment.Fragment
import zio.test.assertTrue

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import scala.collection.immutable.SortedSet

object PostgresCheckSuite extends PostgresDatabaseSpec {
  import DatabaseType.*

  private def set[A: Ordering](as: A*) = SortedSet(as*)

  override val spec = suite("PostgresCheck")(
    suite("pgEnumString")(
      test("read") {
        successRead[MyEnum](fr"select 'foo'::myenum")
      },
      test("write") {
        successWrite[MyEnum](MyEnum.Foo, "myenum")
      },
    ),
    suite("OffsetDateTime")(
      testRead[OffsetDateTime](set(TimestampTZ), set(Timestamp, TimeTZ)),
      testWrite(OffsetDateTime.parse("2015-02-23T01:23:13.000+10:00"), set(TimestampTZ)),
    ),
    suite("Instant")(
      testRead[Instant](set(TimestampTZ), set(Timestamp, TimeTZ)),
      testWrite(Instant.parse("2015-02-23T11:23:13.000Z"), set(TimestampTZ)),
    ),
    suite("LocalDateTime")(
      testRead[LocalDateTime](set(Timestamp)),
      testWrite(LocalDateTime.parse("2015-02-23T01:23:13.000"), set(Timestamp)),
    ),
    suite("LocalDate")(
      testRead[LocalDate](set(Date), set(Timestamp)),
      testWrite(LocalDate.parse("2015-02-23"), set(Date)),
    ),
    suite("LocalTime")(
      testRead[LocalTime](set(Time)),
      testWrite(LocalTime.parse("01:23:13"), set(Time)),
    ),
    suite("OffsetTime")(
      testRead[OffsetTime](set(TimeTZ)),
      testWrite(OffsetTime.parse("01:23:13+10:00"), set(TimeTZ)),
    ),
  )

  private def testRead[A: Get](
    successes: SortedSet[DatabaseType],
    warnings: SortedSet[DatabaseType] = SortedSet.empty,
  ) = {
    val failures = DatabaseType.all -- successes -- warnings

    suite("read")(
      suite("success")(
        successes.toList.map { t =>
          test(t.value) {
            successRead[A](readFragment(t))
          }
        },
      ),
      suite("warn")(
        warnings.toList.map { t =>
          test(t.value) {
            warnRead[A](readFragment(t))
          }
        },
      ),
      suite("failure")(
        failures.toList.map { t =>
          test(t.value) {
            failedRead[A](readFragment(t))
          }
        },
      ),
    )
  }

  private def readFragment(t: DatabaseType) = {
    val fragment = t match {
      case DatabaseType.Bytes => fr"SELECT '2015-02-23'"
      case DatabaseType.Date => fr"SELECT '2015-02-23'"
      case DatabaseType.Timestamp => fr"SELECT '2015-02-23T01:23:13.000'"
      case DatabaseType.TimestampTZ => fr"SELECT '2015-02-23T01:23:13.000+10:00'"
      case DatabaseType.Text => fr"SELECT '2015-02-23'"
      case DatabaseType.Time => fr"SELECT '01:23:13'"
      case DatabaseType.TimeTZ => fr"SELECT '01:23:13+10:00'"
    }
    fr"$fragment::${Fragment.const(t.value)}"
  }

  private def testWrite[A: Put](v: A, successes: SortedSet[DatabaseType]) = {
    val failures = DatabaseType.all -- successes

    suite("write")(
      suite("success")(
        successes.toList.map { t =>
          test(t.value) {
            successWrite[A](v, t.value)
          }
        },
      ),
      suite("failure")(
        failures.toList.map { t =>
          test(t.value) {
            errorWrite[A](v, t.value)
          }
        },
      ),
    )
  }

  private def successRead[A: Get](frag: Fragment) = for {
    analysisResult <- frag.query[A].analysis.transact
    result <- frag.query[A].unique.transact.either
  } yield {
    assertTrue(analysisResult.columnAlignmentErrors == Nil) &&
    assertTrue(result.isRight)
  }

  private def warnRead[A: Get](frag: Fragment) = for {
    analysisResult <- frag.query[A].analysis.transact
    result <- frag.query[A].unique.transact.either
  } yield {
    val errorClasses = analysisResult.columnAlignmentErrors.map(_.getClass)
    assertTrue(errorClasses == List(classOf[ColumnTypeError])) &&
    assertTrue(result.isRight)
  }

  private def failedRead[A: Get](frag: Fragment) = for {
    analysisResult <- frag.query[A].analysis.transact
    result <- frag.query[A].unique.transact.either
  } yield {
    val errorClasses = analysisResult.columnAlignmentErrors.map(_.getClass)
    assertTrue(errorClasses == List(classOf[ColumnTypeError])) &&
    assertTrue(result.isLeft)
  }

  private def successWrite[A: Put](value: A, dbType: String) = for {
    analysisResult <- fr"SELECT $value::${Fragment.const(dbType)}".update.analysis.transact
  } yield {
    assertTrue(analysisResult.parameterAlignmentErrors == Nil)
  }

  private def errorWrite[A: Put](value: A, dbType: String) = for {
    analysisResult <-
      fr"SELECT $value::${Fragment.const(dbType)}".update.analysis.transact
  } yield {
    val errorClasses = analysisResult.parameterAlignmentErrors.map(_.getClass)
    assertTrue(errorClasses == List(classOf[ParameterTypeError]))
  }

  sealed abstract class DatabaseType(val value: String) extends Product with Serializable
  object DatabaseType {
    case object Bytes extends DatabaseType("BYTEA")
    case object Date extends DatabaseType("DATE")
    case object Timestamp extends DatabaseType("TIMESTAMP")
    case object TimestampTZ extends DatabaseType("TIMESTAMPTZ")
    case object Text extends DatabaseType("TEXT")
    case object Time extends DatabaseType("TIME")
    case object TimeTZ extends DatabaseType("TIMETZ")

    implicit val ordering: Ordering[DatabaseType] = Ordering.by(_.value)

    val all = SortedSet(Bytes, Date, Timestamp, TimestampTZ, Text, Time, TimeTZ)
  }

}
