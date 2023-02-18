package doobie.mysql

import doobie.free.connection.ConnectionIO
import doobie.implicits.javasql.*
import doobie.mysql.implicits.*
import doobie.mysql.util.arbitraries.SQLArbitraries.*
import doobie.mysql.util.arbitraries.TimeArbitraries.*
import doobie.syntax.connectionio.*
import doobie.util.Get
import doobie.util.Put
import doobie.util.query.Query0
import doobie.util.update.Update
import doobie.util.update.Update0
import org.scalacheck.Arbitrary
import org.scalacheck.Prop.forAll

import java.time.ZoneOffset

class TypesSuite extends munit.ScalaCheckSuite {
  import MySQLTestTransactor.xa
  import cats.effect.unsafe.implicits.global

  def inOut[A: Get: Put](col: String, a: A): ConnectionIO[A] = for {
    _ <- Update0(s"CREATE TEMPORARY TABLE test (value $col NOT NULL)", None).run
    _ <- Update[A](s"INSERT INTO test VALUES (?)", None).run(a)
    a0 <- Query0[A](s"SELECT value FROM test", None).unique
  } yield a0

  def inOutOpt[A: Get: Put](col: String, a: Option[A]): ConnectionIO[Option[A]] =
    for {
      _ <- Update0(s"CREATE TEMPORARY TABLE test (value $col)", None).run
      _ <- Update[Option[A]](s"INSERT INTO test VALUES (?)", None).run(a)
      a0 <- Query0[Option[A]](s"SELECT value FROM test", None).unique
    } yield a0

  private def testInOut[A](col: String)(implicit m: Get[A], p: Put[A], arbitrary: Arbitrary[A]): Unit = {
    testInOutCustomize(col)
  }

  private def testInOutCustomize[A](
    col: String,
    skipNone: Boolean = false,
    expected: A => A = identity[A](_),
  )(implicit m: Get[A], p: Put[A], arbitrary: Arbitrary[A]): Unit = {
    val gen = arbitrary.arbitrary

    test(s"Mapping for $col as ${m.typeStack} - write+read $col as ${m.typeStack}") {
      forAll(gen) { (t: A) =>
        val actual = inOut(col, t).transact(xa).attempt.unsafeRunSync()
        assertEquals(actual.map(expected(_)), Right(expected(t)))
      }
    }
    test(s"Mapping for $col as ${m.typeStack} - write+read $col as Option[${m.typeStack}] (Some)") {
      forAll(gen) { (t: A) =>
        val actual = inOutOpt[A](col, Some(t)).transact(xa).attempt.unsafeRunSync()
        assertEquals(actual.map(_.map(expected(_))), Right(Some(expected(t))))
      }
    }
    if (!skipNone) {
      test(s"Mapping for $col as ${m.typeStack} - write+read $col as Option[${m.typeStack}] (None)") {
        assertEquals(inOutOpt[A](col, None).transact(xa).attempt.unsafeRunSync(), Right(None))
      }
    }
  }

  testInOutCustomize[java.time.OffsetDateTime](
    "timestamp(6)",
    skipNone = true, // returns the current timestamp, lol
    _.withOffsetSameInstant(ZoneOffset.UTC),
  )
  testInOutCustomize[java.time.Instant](
    "timestamp(6)",
    skipNone = true, // returns the current timestamp, lol
  )

  testInOut[java.sql.Timestamp]("datetime(6)")
  testInOut[java.time.LocalDateTime]("datetime(6)")

  testInOut[java.sql.Date]("date")
  testInOut[java.time.LocalDate]("date")

  testInOut[java.sql.Time]("time")
  testInOut[java.time.LocalTime]("time(6)")
}
