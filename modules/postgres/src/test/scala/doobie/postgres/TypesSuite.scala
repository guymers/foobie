// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import doobie.free.connection.ConnectionIO
import doobie.implicits.javasql.*
import doobie.postgres.enums.*
import doobie.postgres.implicits.*
import doobie.postgres.util.arbitraries.SQLArbitraries.*
import doobie.postgres.util.arbitraries.TimeArbitraries.*
import doobie.syntax.connectionio.*
import doobie.util.Get
import doobie.util.Put
import doobie.util.arbitraries.StringArbitraries.*
import doobie.util.meta.Meta
import doobie.util.update.Update
import doobie.util.update.Update0
import org.postgresql.geometric.*
import org.postgresql.util.*
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.math.BigDecimal as JBigDecimal
import java.net.InetAddress
import java.time.ZoneOffset
import java.util.UUID

// Establish that we can write and read various types.
trait PostgresInstanceCheckSuite { self: munit.ScalaCheckSuite =>
  import PostgresTestTransactor.xa
  import cats.effect.unsafe.implicits.global

  def inOut[A: Get: Put](col: String, a: A): ConnectionIO[A] = for {
    _ <- Update0(s"CREATE TEMPORARY TABLE TEST (value $col NOT NULL)", None).run
    a0 <- Update[A](s"INSERT INTO TEST VALUES (?)", None).withUniqueGeneratedKeys[A]("value")(a)
  } yield a0

  def inOutOpt[A: Get: Put](col: String, a: Option[A]): ConnectionIO[Option[A]] =
    for {
      _ <- Update0(s"CREATE TEMPORARY TABLE TEST (value $col)", None).run
      a0 <- Update[Option[A]](s"INSERT INTO TEST VALUES (?)", None).withUniqueGeneratedKeys[Option[A]]("value")(a)
    } yield a0

  def testInOut[A](col: String)(implicit m: Get[A], p: Put[A], arbitrary: Arbitrary[A]) = {
    testInOutWithCustomGen(col, arbitrary.arbitrary)
  }

  def testInOutTweakExpected[A](col: String)(f: A => A)(implicit m: Get[A], p: Put[A], arbitrary: Arbitrary[A]) = {
    testInOutWithCustomGen(col, arbitrary.arbitrary, f)
  }

  def testInOut[A](col: String, a: A)(implicit m: Get[A], p: Put[A]) = {
    test(s"Mapping for $col as ${m.typeStack} - write+read $col as ${m.typeStack}") {
      assertEquals(inOut(col, a).transact(xa).attempt.unsafeRunSync(), Right(a))
    }
    test(s"Mapping for $col as ${m.typeStack} - write+read $col as Option[${m.typeStack}] (Some)") {
      assertEquals(inOutOpt[A](col, Some(a)).transact(xa).attempt.unsafeRunSync(), Right(Some(a)))
    }
    test(s"Mapping for $col as ${m.typeStack} - write+read $col as Option[${m.typeStack}] (None)") {
      assertEquals(inOutOpt[A](col, None).transact(xa).attempt.unsafeRunSync(), Right(None))
    }
  }

  def testInOutWithCustomGen[A](col: String, gen: Gen[A], expected: A => A = identity[A](_))(implicit
    m: Get[A],
    p: Put[A],
  ) = {
    test(s"Mapping for $col as ${m.typeStack} - write+read $col as ${m.typeStack}") {
      forAll(gen) { (t: A) => assertEquals(inOut(col, t).transact(xa).attempt.unsafeRunSync(), Right(expected(t))) }
    }
    test(s"Mapping for $col as ${m.typeStack} - write+read $col as Option[${m.typeStack}] (Some)") {
      forAll(gen) { (t: A) =>
        assertEquals(inOutOpt[A](col, Some(t)).transact(xa).attempt.unsafeRunSync(), Right(Some(expected(t))))
      }
    }
    test(s"Mapping for $col as ${m.typeStack} - write+read $col as Option[${m.typeStack}] (None)") {
      assertEquals(inOutOpt[A](col, None).transact(xa).attempt.unsafeRunSync(), Right(None))
    }
  }

  def skip(col: String, msg: String = "not yet implemented") =
    test(s"Mapping for $col ($msg)".ignore) {}
}

class TypesSuite extends munit.ScalaCheckSuite with PostgresInstanceCheckSuite {

  // 8.1 Numeric Types
  testInOut[Short]("smallint")
  testInOut[Int]("integer")
  testInOut[Long]("bigint")
  testInOut[BigDecimal]("decimal")
  testInOut[BigDecimal]("numeric")
  testInOut[Float]("real")
  testInOut[Double]("double precision")

  // 8.2 Monetary Types
  skip("pgmoney", "getObject returns Double")

  // 8.3 Character Types"
  testInOut[String]("character varying")
  testInOut[String]("varchar")
  testInOutWithCustomGen("character(6)", nLongString(6))
  testInOutWithCustomGen("char(6)", nLongString(6))
  testInOut[String]("text")

  // 8.4 Binary Types
  testInOut[List[Byte]]("bytea")
  testInOut[Vector[Byte]]("bytea")

  // 8.5 Date/Time Types"

  /*
      timestamp
      The allowed range of p is from 0 to 6 for the timestamp and interval types.
   */
  testInOut[java.sql.Timestamp]("timestamptz")
  testInOut[java.time.Instant]("timestamptz")
  testInOutTweakExpected[java.time.OffsetDateTime]("timestamptz")(_.withOffsetSameInstant(ZoneOffset.UTC)) // +148488-07-03T02:38:17Z != +148488-07-03T00:00-02:38:17

  /*
    local date & time (not an instant in time)
   */
  testInOut[java.time.LocalDateTime]("timestamp")

  testInOut[java.sql.Date]("date")
  testInOut[java.time.LocalDate]("date")

  testInOut[java.sql.Time]("time")
  testInOut[java.time.LocalTime]("time")

  skip("time with time zone")
  testInOut("interval", new PGInterval(1, 2, 3, 4, 5, 6.7))

  // 8.6 Boolean Type
  testInOut[Boolean]("boolean")

  // 8.7 Enumerated Types
  testInOut("myenum", MyEnum.Foo: MyEnum)

  // as scala.Enumeration
  implicit val MyEnumMeta: Meta[MyScalaEnum.Value] = pgEnum(MyScalaEnum, "myenum")
  testInOut("myenum", MyScalaEnum.foo)

  // as java.lang.Enum
  implicit val MyJavaEnumMeta: Meta[MyJavaEnum] = pgJavaEnum[MyJavaEnum]("myenum")
  testInOut("myenum", MyJavaEnum.bar)

  // 8.8 Geometric Types
  testInOut("box", new PGbox(new PGpoint(1, 2), new PGpoint(3, 4)))
  testInOut("circle", new PGcircle(new PGpoint(1, 2), 3))
  testInOut("lseg", new PGlseg(new PGpoint(1, 2), new PGpoint(3, 4)))
  testInOut("path", new PGpath(Array(new PGpoint(1, 2), new PGpoint(3, 4)), false))
  testInOut("path", new PGpath(Array(new PGpoint(1, 2), new PGpoint(3, 4)), true))
  testInOut("point", new PGpoint(1, 2))
  testInOut("polygon", new PGpolygon(Array(new PGpoint(1, 2), new PGpoint(3, 4))))
  skip("line", "doc says \"not fully implemented\"")

  // 8.9 Network Address Types
  testInOut("inet", InetAddress.getByName("123.45.67.8"))
  skip("inet", "no suitable JDK type")
  skip("macaddr", "no suitable JDK type")

  // 8.10 Bit String Types
  skip("bit")
  skip("bit varying")

  // 8.11 Text Search Types
  skip("tsvector")
  skip("tsquery")

  // 8.12 UUID Type
  testInOut[UUID]("uuid")

  // 8.13 XML Type
  skip("xml")

  // 8.14 JSON Type
  skip("json")

  // 8.15 Arrays
  skip("bit[]", "Requires a cast")
  skip("smallint[]", "always comes back as Array[Int]")
  testInOut[List[Int]]("integer[]")
  testInOut[List[Long]]("bigint[]")
  testInOut[List[Float]]("real[]")
  testInOut[List[Double]]("double precision[]")
  testInOut[List[String]]("varchar[]")
  testInOut[List[UUID]]("uuid[]")
  testInOut("numeric[]", List[JBigDecimal](BigDecimal("3.14").bigDecimal, BigDecimal("42.0").bigDecimal))
  testInOut[List[BigDecimal]]("numeric[]", List[BigDecimal](BigDecimal("3.14"), BigDecimal("42.0")))

  // 8.16 Structs
  skip("structs")

  // 8.17 Range Types
  skip("int4range")
  skip("int8range")
  skip("numrange")
  skip("tsrange")
  skip("tstzrange")
  skip("daterange")
  skip("custom")

  // hstore
  testInOut[Map[String, String]]("hstore")
}
