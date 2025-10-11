// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import cats.syntax.foldable.*
import cats.syntax.show.*
import doobie.free.connection.ConnectionIO
import doobie.postgres.enums.*
import doobie.postgres.instances.array.*
import doobie.postgres.instances.enumeration.*
import doobie.postgres.instances.geometric.*
import doobie.postgres.instances.inet.*
import doobie.postgres.instances.interval.*
import doobie.postgres.instances.map.*
import doobie.postgres.instances.uuid.*
import doobie.syntax.string.*
import doobie.util.Get
import doobie.util.Put
import doobie.util.Read
import doobie.util.Write
import doobie.util.fragment.Fragment
import doobie.util.meta.Meta
import org.postgresql.geometric.*
import org.postgresql.util.*
import zio.ZIO
import zio.ZLayer
import zio.test.Gen
import zio.test.Live
import zio.test.TestAspect
import zio.test.assertCompletes
import zio.test.assertTrue
import zio.test.check
import zoobie.Transactor

import java.math.BigDecimal as JBigDecimal
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.util.UUID

object PostgresTypesSuite extends PostgresDatabaseSpec {
  import doobie.postgres.util.generators.SQLGenerators.*
  import doobie.postgres.util.generators.TimeGenerators.*

  // a faster way of generating strings
  private val genString = Gen.bounded(0, 2048)(i => Gen.fromRandom(_.nextString(i)))

  private val genBigDecimal = Gen.bigDecimal(Double.MinValue, Double.MaxValue)

  override val spec = suite("PostgresTypes")(
    // 8.1 Numeric Types
    suiteGetPut[Short]("smallint", Gen.short),
    suiteGetPut[Int]("integer", Gen.int),
    suiteGetPut[Long]("bigint", Gen.long),
    suiteGetPut[BigDecimal]("decimal", genBigDecimal),
    suiteGetPut[BigDecimal]("numeric", genBigDecimal),
    suiteGetPut[Float]("real", Gen.float),
    suiteGetPut[Double]("double precision", Gen.double),

    // 8.2 Monetary Types
    skip("pgmoney", "getObject returns Double"),

    // 8.3 Character Types"
    suiteGetPut[String]("character varying", genString),
    suiteGetPut[String]("varchar", genString),
    suiteGetPut[String]("character(6)", Gen.alphaNumericStringBounded(0, 6).map(_.padTo(6, ' '))),
    suiteGetPut[String]("char(6)", Gen.alphaNumericStringBounded(0, 6).map(_.padTo(6, ' '))),
    suiteGetPut[String]("text", genString),

    // 8.4 Binary Types
    suiteGetPut[List[Byte]]("bytea", genString.map(_.getBytes(StandardCharsets.UTF_8).toList)),

    // 8.5 Date/Time Types
    suiteGetPut[java.sql.Timestamp]("timestamptz", genSQLTimestamp),
    suiteGetPut[java.time.Instant]("timestamptz", genInstant),
    // +148488-07-03T02:38:17Z != +148488-07-03T00:00-02:38:17
    suiteGetPut[java.time.OffsetDateTime]("timestamptz", genOffsetDateTime, _.withOffsetSameInstant(ZoneOffset.UTC)),
    suiteGetPut[java.time.LocalDateTime]("timestamp", genLocalDateTime),
    suiteGetPut[java.sql.Date]("date", genSQLDate),
    suiteGetPut[java.time.LocalDate]("date", genLocalDate),
    suiteGetPut[java.sql.Time]("time", genSQLTime),
    suiteGetPut[java.time.LocalTime]("time", genLocalTime),
    suiteGetPut[java.time.OffsetTime]("time with time zone", genOffsetTime),
    suiteGetPut("interval", Gen.const(new PGInterval(1, 2, 3, 4, 5, 6.7))),
    suiteGetPut[java.time.ZoneId]("text", Gen.zoneId),

    // 8.6 Boolean Type
    suiteGetPut[Boolean]("boolean", Gen.boolean),

    // 8.7 Enumerated Types
    suiteGetPut("myenum", Gen.const(MyEnum.Foo: MyEnum)),
    // as scala.Enumeration
    {
      implicit val MyEnumMeta: Meta[MyScalaEnum.Value] = pgEnum(MyScalaEnum, "myenum")
      suiteGetPut("myenum", Gen.const(MyScalaEnum.foo))
    },
    // as java.lang.Enum
    {
      implicit val MyJavaEnumMeta: Meta[MyJavaEnum] = pgJavaEnum[MyJavaEnum]("myenum")
      suiteGetPut("myenum", Gen.const(MyJavaEnum.bar))
    },

    // 8.8 Geometric Types
    suiteGetPut("box", Gen.const(new PGbox(new PGpoint(1, 2), new PGpoint(3, 4)))),
    suiteGetPut("circle", Gen.const(new PGcircle(new PGpoint(1, 2), 3))),
    suiteGetPut("lseg", Gen.const(new PGlseg(new PGpoint(1, 2), new PGpoint(3, 4)))),
    suiteGetPut("path", Gen.const(new PGpath(Array(new PGpoint(1, 2), new PGpoint(3, 4)), false))),
    suiteGetPut("path", Gen.const(new PGpath(Array(new PGpoint(1, 2), new PGpoint(3, 4)), true))),
    suiteGetPut("point", Gen.const(new PGpoint(1, 2))),
    suiteGetPut("polygon", Gen.const(new PGpolygon(Array(new PGpoint(1, 2), new PGpoint(3, 4))))),
    skip("line", "doc says \"not fully implemented\""),

    // 8.9 Network Address Types
    suiteGetPut("inet", Gen.const(InetAddress.getByName("123.45.67.8"))),
    skip("macaddr", "no suitable JDK type"),

    // 8.10 Bit String Types
    skip("bit"),
    skip("bit varying"),

    // 8.11 Text Search Types
    skip("tsvector"),
    skip("tsquery"),

    // 8.12 UUID Type
    suiteGetPut[UUID]("uuid", Gen.uuid),

    // 8.13 XML Type
    skip("xml"),

    // 8.14 JSON Type
    skip("json"),

    // 8.15 Arrays
    suiteGetPut[List[Boolean]]("bit[]", Gen.listOfBounded(0, 10)(Gen.boolean)),
    suiteGetPut[List[Short]]("smallint[]", Gen.listOfBounded(0, 10)(Gen.short)),
    suiteGetPut[List[Int]]("integer[]", Gen.listOfBounded(0, 10)(Gen.int)),
    suiteGetPut[List[Long]]("bigint[]", Gen.listOfBounded(0, 10)(Gen.long)),
    suiteGetPut[List[Float]]("real[]", Gen.listOfBounded(0, 10)(Gen.float)),
    suiteGetPut[List[Double]]("double precision[]", Gen.listOfBounded(0, 10)(Gen.double)),
    suiteGetPut[List[String]]("varchar[]", Gen.listOfBounded(0, 10)(genString)),
    suiteGetPut[List[UUID]]("uuid[]", Gen.listOfBounded(0, 10)(Gen.uuid)),
    suiteGetPut[List[java.sql.Date]]("date[]", Gen.listOfBounded(0, 10)(genSQLDateArray)),
    suiteGetPut[List[java.time.LocalDate]]("date[]", Gen.listOfBounded(0, 10)(genLocalDateArray)),
    suiteGetPut[List[java.sql.Timestamp]]("timestamp[]", Gen.listOfBounded(0, 10)(genSQLTimestampArray)),
    suiteGetPut("numeric[]", Gen.const(List[JBigDecimal](BigDecimal("3.14").bigDecimal, BigDecimal("42.0").bigDecimal))),
    suiteGetPut("numeric[]", Gen.const(List[BigDecimal](BigDecimal("3.14"), BigDecimal("42.0")))),

    // 8.16 Structs
    skip("structs"),

    // 8.17 Range Types
    skip("int4range"),
    skip("int8range"),
    skip("numrange"),
    skip("tsrange"),
    skip("tstzrange"),
    skip("daterange"),
    skip("custom"),

    // hstore
    suiteGetPut[Map[String, String]]("hstore", Gen.listOfBounded(0, 10)(genString <*> genString).map(_.toMap)),
  )

  def suiteGetPut[A](
    columnType: String,
    gen: Gen[Any, A],
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
      },
    ).provideSomeLayerShared[Transactor](ZLayer.scoped(withTables(columnType)))
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
    fr"INSERT INTO $table (value) VALUES ($a)".update.withUniqueGeneratedKeys[A]("value")
  }

  private def withTables(columnType: String) = for {
    notNull <- withTable(columnType, nullable = false)
    nullable <- withTable(columnType, nullable = true)
  } yield (notNull, nullable)

  private def withTable(columnType: String, nullable: Boolean) = for {
    uuid <- Live.live(zio.Random.nextUUID)
    table = Fragment.const(show"test_types_${uuid.toString.replaceAll("-", "")}")
    col = Fragment.const(columnType)
    create = fr"CREATE UNLOGGED TABLE $table (value $col ${if (nullable) fr"" else fr"NOT NULL"})".update.run
    drop = fr"DROP TABLE IF EXISTS $table".update.run
    _ <- ZIO.acquireRelease(create.transact)(_ => drop.transact.ignoreLogged)
  } yield table

  def skip(columnType: String, msg: String = "not yet implemented") = {
    test(show"column $columnType ($msg)") {
      assertCompletes
    } @@ TestAspect.ignore
  }
}
