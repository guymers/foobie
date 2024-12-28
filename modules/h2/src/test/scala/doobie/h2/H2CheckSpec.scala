// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.h2

import doobie.h2.implicits.*
import doobie.syntax.string.*
import doobie.util.Get
import doobie.util.analysis.ColumnTypeError
import doobie.util.fragment.Fragment
import zio.test.assertTrue

import java.time.*
import java.util.UUID
import scala.collection.immutable.SortedSet

object H2CheckSpec extends H2DatabaseSpec {
  import DatabaseType.*

  override val spec = suite("H2Check")(
    suite("Boolean")(
      test("unascribed 'true'") {
        fr"select true".query[Boolean].analysis.transact.map { a =>
          assertTrue(a.alignmentErrors == Nil)
        }
      },
      test("ascribed BIT") {
        fr"select true::BIT".query[Boolean].analysis.transact.map { a =>
          assertTrue(a.alignmentErrors == Nil)
        }
      },
      test("ascribed BOOLEAN") {
        fr"select true::BOOLEAN".query[Boolean].analysis.transact.map { a =>
          assertTrue(a.alignmentErrors == Nil)
        }
      },
    ),
    suite("UUID")(
      test("unascribed UUID") {
        fr"select random_uuid()".query[UUID].analysis.transact.map { a =>
          assertTrue(a.alignmentErrors == Nil)
        }
      },
      test("ascribed UUID") {
        fr"select random_uuid()::UUID".query[UUID].analysis.transact.map { a =>
          assertTrue(a.alignmentErrors == Nil)
        }
      },
    ),
    suite("OffsetDateTime")(
      suiteGet[OffsetDateTime](set(TimestampTZ)),
    ),
    suite("Instant")(
      suiteGet[Instant](set(TimestampTZ)),
    ),
    suite("LocalDateTime")(
      suiteGet[LocalDateTime](set(Timestamp)),
    ),
    suite("LocalDate")(
      suiteGet[LocalDate](set(Date)),
    ),
    suite("LocalTime")(
      suiteGet[LocalTime](set(DatabaseType.Time)),
    ),
    suite("OffsetTime")(
      suiteGet[OffsetTime](set(TimeTZ)),
    ),
  )

  private def suiteGet[A: Get](successes: SortedSet[DatabaseType]) = {
    val failures = DatabaseType.all -- successes

    suite("read")(
      suite("success")(
        successes.toList.map { t =>
          test(t.value) {
            readFragment(t).query[A].analysis.transact.map { result =>
              assertTrue(result.alignmentErrors == Nil)
            }
          }
        },
      ),
      suite("failure")(
        failures.toList.map { t =>
          test(t.value) {
            readFragment(t).query[A].analysis.transact.map { result =>
              val errorClasses = result.alignmentErrors.map(_.getClass)
              assertTrue(errorClasses == List(classOf[ColumnTypeError]))
            }
          }
        },
      ),
    )
  }

  private def readFragment(t: DatabaseType) = {
    val fragment = t match {
      case DatabaseType.Date => fr"SELECT '2000-01-02'"
      case DatabaseType.Timestamp => fr"SELECT '2000-01-02T01:02:03.000'"
      case DatabaseType.TimestampTZ => fr"SELECT '2000-01-02T01:02:03.000+10:00'"
      case DatabaseType.Text => fr"SELECT '2000-01-02'"
      case DatabaseType.Time => fr"SELECT '01:02:03'"
      case DatabaseType.TimeTZ => fr"SELECT '01:02:03+10:00'"
    }
    fr"$fragment::${Fragment.const(t.value)}"
  }

  sealed abstract class DatabaseType(val value: String) extends Product with Serializable
  object DatabaseType {
    case object Date extends DatabaseType("DATE")
    case object Timestamp extends DatabaseType("TIMESTAMP")
    case object TimestampTZ extends DatabaseType("TIMESTAMP WITH TIME ZONE")
    case object Text extends DatabaseType("TEXT")
    case object Time extends DatabaseType("TIME")
    case object TimeTZ extends DatabaseType("TIME WITH TIME ZONE")

    implicit val ordering: Ordering[DatabaseType] = Ordering.by(_.value)

    val all = SortedSet(Date, Timestamp, TimestampTZ, Text, Time, TimeTZ)
  }

  private def set[A: Ordering](as: A*) = SortedSet(as*)

}
