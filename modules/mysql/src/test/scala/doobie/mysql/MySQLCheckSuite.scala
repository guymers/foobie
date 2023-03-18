package doobie.mysql

import doobie.syntax.string.*
import doobie.util.Get
import doobie.util.analysis.ColumnTypeError
import doobie.util.fragment.Fragment
import zio.test.assertTrue

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import scala.collection.immutable.SortedSet

object MySQLCheckSuite extends MySQLDatabaseSpec {
  import DatabaseType.*

  private def set[A: Ordering](as: A*) = SortedSet(as*)

  override val spec = suite("MySQLCheckSuite")(
    suite("OffsetDateTime")(
      testRead[OffsetDateTime](set(TimestampTZ), set(Date, Text, Time, Timestamp)),
    ),
    suite("Instant")(
      testRead[Instant](set(TimestampTZ), set(Date, Text, Time, Timestamp)),
    ),
    suite("LocalDateTime")(
      testRead[LocalDateTime](set(Timestamp), set(Date, Text, Time, TimestampTZ)),
    ),
    suite("LocalDate")(
      testRead[LocalDate](set(Date), set(Text, Time, Timestamp, TimestampTZ)),
    ),
    suite("LocalTime")(
      testRead[LocalTime](set(Time), set(Date, Text, Timestamp, TimestampTZ)),
    ),
    suite("OffsetTime")(
      testRead[OffsetTime](set(), set(Date, Text, Time, Timestamp, TimestampTZ)),
    ),
  )

  private def testRead[A: Get](
    successes: SortedSet[DatabaseType],
    warnings: SortedSet[DatabaseType],
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

  // note selecting from a table because a value cannot be cast to a timestamp
  // and casting returns a nullable column
  private def readFragment(t: DatabaseType) = {
    t match {
      case DatabaseType.Date => fr"SELECT c_date FROM test LIMIT 1"
      case DatabaseType.Int => fr"SELECT c_integer FROM test LIMIT 1"
      case DatabaseType.Timestamp => fr"SELECT c_datetime FROM test LIMIT 1"
      case DatabaseType.TimestampTZ => fr"SELECT c_timestamp FROM test LIMIT 1"
      case DatabaseType.Text => fr"SELECT '2015-02-23'"
      case DatabaseType.Time => fr"SELECT c_time FROM test LIMIT 1"
    }
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

  sealed abstract class DatabaseType(val value: String) extends Product with Serializable
  object DatabaseType {
    case object Date extends DatabaseType("DATE")
    case object Int extends DatabaseType("INT")
    case object Timestamp extends DatabaseType("TIMESTAMP")
    case object TimestampTZ extends DatabaseType("TIMESTAMPTZ")
    case object Text extends DatabaseType("TEXT")
    case object Time extends DatabaseType("TIME")

    implicit val ordering: Ordering[DatabaseType] = Ordering.by(_.value)

    val all = SortedSet(Date, Int, Timestamp, TimestampTZ, Text, Time)
  }

}
