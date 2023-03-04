// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util.meta

import cats.Invariant
import cats.Show
import cats.data.NonEmptyList
import doobie.enumerated.JdbcType
import doobie.util.Get
import doobie.util.Put
import org.tpolecat.typename.TypeName

import java.sql.PreparedStatement
import java.sql.ResultSet
import scala.reflect.ClassTag

/**
 * Convenience for introducing a symmetric `Get`/`Put` pair into implicit scope,
 * and for deriving new symmetric pairs. It's important to understand that
 * `Meta` should never be demanded by user methods; instead demand both `Get`
 * and `Put`. The reason for this is that while `Meta` implies `Get` and `Put`,
 * the presence of both `Get` and `Put` does *not* imply `Meta`.
 */
final class Meta[A](val get: Get[A], val put: Put[A]) {

  /**
   * Meta is an invariant functor. Prefer `timap` as it provides for better
   * diagnostics.
   */
  def imap[B](f: A => B)(g: B => A): Meta[B] =
    new Meta(get.map(f), put.contramap(g))

  /** Variant of `imap` that takes a type tag, to aid in diagnostics. */
  def timap[B: TypeName](f: A => B)(g: B => A): Meta[B] =
    new Meta(get.tmap(f), put.tcontramap(g))

  /** Variant of `timap` that allows the reading conversion to fail. */
  def tiemap[B: TypeName](f: A => Either[String, B])(g: B => A)(implicit showA: Show[A]): Meta[B] =
    new Meta(get.temap(f), put.contramap(g))

}

object Meta {
  import doobie.enumerated.JdbcType.{Boolean as JdbcBoolean, *}

  /** Summon the `Meta` instance if possible. */
  def apply[A](implicit ev: Meta[A]): ev.type = ev

  /** @group Typeclass Instances */
  implicit val MetaInvariant: Invariant[Meta] = new Invariant[Meta] {
    override def imap[A, B](fa: Meta[A])(f: A => B)(g: B => A) = fa.imap(f)(g)
  }

  /**
   * Module of constructors for "basic" JDBC types.
   * @group Constructors
   */
  object Basic {

    def many[A: TypeName](
      jdbcTarget: NonEmptyList[JdbcType],
      jdbcSource: NonEmptyList[JdbcType],
      jdbcSourceSecondary: List[JdbcType],
      get: (ResultSet, Int) => A,
      put: (PreparedStatement, Int, A) => Unit,
      update: (ResultSet, Int, A) => Unit,
    ): Meta[A] =
      new Meta(
        Get.Basic.many(jdbcSource, jdbcSourceSecondary, get),
        Put.Basic.many(jdbcTarget, put, update),
      )

    def one[A: TypeName](
      jdbcType: JdbcType,
      jdbcSourceSecondary: List[JdbcType],
      get: (ResultSet, Int) => A,
      put: (PreparedStatement, Int, A) => Unit,
      update: (ResultSet, Int, A) => Unit,
    ): Meta[A] =
      new Meta(
        Get.Basic.one(jdbcType, jdbcSourceSecondary, get),
        Put.Basic.one(jdbcType, put, update),
      )

    def oneObject[A: TypeName](
      jdbcType: JdbcType,
      jdbcSourceSecondary: List[JdbcType],
      clazz: Class[A],
    ): Meta[A] = one(
      jdbcType = jdbcType,
      jdbcSourceSecondary = jdbcSourceSecondary,
      _.getObject(_, clazz),
      _.setObject(_, _),
      _.updateObject(_, _),
    )
  }

  /**
   * Module of constructors for "advanced" JDBC types.
   * @group Constructors
   */
  object Advanced {

    def many[A: TypeName](
      jdbcTypes: NonEmptyList[JdbcType],
      schemaTypes: NonEmptyList[String],
      get: (ResultSet, Int) => A,
      put: (PreparedStatement, Int, A) => Unit,
      update: (ResultSet, Int, A) => Unit,
    ): Meta[A] =
      new Meta(
        Get.Advanced.many(jdbcTypes, schemaTypes, get),
        Put.Advanced.many(jdbcTypes, schemaTypes, put, update),
      )

    def one[A: TypeName](
      jdbcTypes: JdbcType,
      schemaTypes: NonEmptyList[String],
      get: (ResultSet, Int) => A,
      put: (PreparedStatement, Int, A) => Unit,
      update: (ResultSet, Int, A) => Unit,
    ): Meta[A] =
      new Meta(
        Get.Advanced.one(jdbcTypes, schemaTypes, get),
        Put.Advanced.one(jdbcTypes, schemaTypes, put, update),
      )

    def array[A >: Null <: AnyRef](
      elementType: String,
      schemaH: String,
      schemaT: String*,
    ): Meta[Array[A]] =
      new Meta[Array[A]](
        Get.Advanced.array[A](NonEmptyList(schemaH, schemaT.toList)),
        Put.Advanced.array[A](NonEmptyList(schemaH, schemaT.toList), elementType),
      )

    def other[A >: Null <: AnyRef: TypeName: ClassTag](
      schemaH: String,
      schemaT: String*,
    ): Meta[A] =
      new Meta(
        Get.Advanced.other[A](NonEmptyList(schemaH, schemaT.toList)),
        Put.Advanced.other[A](NonEmptyList(schemaH, schemaT.toList)),
      )

  }

  /** @group Instances */
  implicit val ByteMeta: Meta[Byte] =
    Basic.one[Byte](
      TinyInt,
      List(SmallInt, Integer, BigInt, Real, Float, Double, Decimal, Numeric, Bit, Char, VarChar, LongVarChar),
      _.getByte(_),
      _.setByte(_, _),
      _.updateByte(_, _),
    )

  /** @group Instances */
  implicit val ShortMeta: Meta[Short] =
    Basic.one[Short](
      SmallInt,
      List(TinyInt, Integer, BigInt, Real, Float, Double, Decimal, Numeric, Bit, Char, VarChar, LongVarChar),
      _.getShort(_),
      _.setShort(_, _),
      _.updateShort(_, _),
    )

  /** @group Instances */
  implicit val IntMeta: Meta[Int] =
    Basic.one[Int](
      Integer,
      List(TinyInt, SmallInt, BigInt, Real, Float, Double, Decimal, Numeric, Bit, Char, VarChar, LongVarChar),
      _.getInt(_),
      _.setInt(_, _),
      _.updateInt(_, _),
    )

  /** @group Instances */
  implicit val LongMeta: Meta[Long] =
    Basic.one[Long](
      BigInt,
      List(TinyInt, Integer, SmallInt, Real, Float, Double, Decimal, Numeric, Bit, Char, VarChar, LongVarChar),
      _.getLong(_),
      _.setLong(_, _),
      _.updateLong(_, _),
    )

  /** @group Instances */
  implicit val FloatMeta: Meta[Float] =
    Basic.one[Float](
      Real,
      List(TinyInt, Integer, SmallInt, BigInt, Float, Double, Decimal, Numeric, Bit, Char, VarChar, LongVarChar),
      _.getFloat(_),
      _.setFloat(_, _),
      _.updateFloat(_, _),
    )

  /** @group Instances */
  implicit val DoubleMeta: Meta[Double] =
    Basic.many[Double](
      NonEmptyList.of(Double),
      NonEmptyList.of(Float, Double),
      List(TinyInt, Integer, SmallInt, BigInt, Float, Real, Decimal, Numeric, Bit, Char, VarChar, LongVarChar),
      _.getDouble(_),
      _.setDouble(_, _),
      _.updateDouble(_, _),
    )

  /** @group Instances */
  implicit val BigDecimalMeta: Meta[java.math.BigDecimal] =
    Basic.many[java.math.BigDecimal](
      NonEmptyList.of(Numeric),
      NonEmptyList.of(Decimal, Numeric),
      List(TinyInt, Integer, SmallInt, BigInt, Float, Double, Real, Bit, Char, VarChar, LongVarChar),
      _.getBigDecimal(_),
      _.setBigDecimal(_, _),
      _.updateBigDecimal(_, _),
    )

  /** @group Instances */
  implicit val BooleanMeta: Meta[Boolean] =
    Basic.many[Boolean](
      NonEmptyList.of(Bit, JdbcBoolean),
      NonEmptyList.of(Bit, JdbcBoolean),
      List(TinyInt, Integer, SmallInt, BigInt, Float, Double, Real, Decimal, Numeric, Char, VarChar, LongVarChar),
      _.getBoolean(_),
      _.setBoolean(_, _),
      _.updateBoolean(_, _),
    )

  /** @group Instances */
  implicit val StringMeta: Meta[String] =
    Basic.many[String](
      NonEmptyList.of(VarChar, Char, LongVarChar, NChar, NVarChar, LongnVarChar),
      NonEmptyList.of(Char, VarChar, LongVarChar, NChar, NVarChar, LongnVarChar),
      List(
        TinyInt,
        Integer,
        SmallInt,
        BigInt,
        Float,
        Double,
        Real,
        Decimal,
        Numeric,
        Bit,
        Binary,
        VarBinary,
        LongVarBinary,
        Date,
        Time,
        Timestamp,
      ),
      _.getString(_),
      _.setString(_, _),
      _.updateString(_, _),
    )

  /** @group Instances */
  implicit val ByteArrayMeta: Meta[Array[Byte]] =
    Basic.many[Array[Byte]](
      NonEmptyList.of(Binary, VarBinary, LongVarBinary),
      NonEmptyList.of(Binary, VarBinary),
      List(LongVarBinary),
      _.getBytes(_),
      _.setBytes(_, _),
      _.updateBytes(_, _),
    )

  /** @group Instances */
  implicit val ScalaBigDecimalMeta: Meta[BigDecimal] =
    BigDecimalMeta.imap(BigDecimal.apply)(_.bigDecimal)

  // SQL

  /** @group Instances */
  implicit val DateMeta: Meta[java.sql.Date] =
    Basic.one[java.sql.Date](
      Date,
      List(Char, VarChar, LongVarChar, Timestamp),
      _.getDate(_),
      _.setDate(_, _),
      _.updateDate(_, _),
    )

  /** @group Instances */
  implicit val TimeMeta: Meta[java.sql.Time] =
    Basic.one[java.sql.Time](
      Time,
      List(Char, VarChar, LongVarChar, Timestamp),
      _.getTime(_),
      _.setTime(_, _),
      _.updateTime(_, _),
    )

  /** @group Instances */
  implicit val TimestampMeta: Meta[java.sql.Timestamp] =
    Basic.one[java.sql.Timestamp](
      Timestamp,
      List(Char, VarChar, LongVarChar, Date, Time),
      _.getTimestamp(_),
      _.setTimestamp(_, _),
      _.updateTimestamp(_, _),
    )

  // Instances for Java time classes that follow the JDBC specification.

  /** @group Instances */
  implicit val JavaOffsetDateTimeMeta: Meta[java.time.OffsetDateTime] =
    Basic.oneObject(TimestampWithTimezone, Nil, classOf[java.time.OffsetDateTime])

  /** @group Instances */
  implicit val JavaLocalDateMeta: Meta[java.time.LocalDate] =
    Basic.oneObject(Date, Nil, classOf[java.time.LocalDate])

  /** @group Instances */
  implicit val JavaLocalTimeMeta: Meta[java.time.LocalTime] =
    Basic.oneObject(Time, Nil, classOf[java.time.LocalTime])

  /** @group Instances */
  implicit val JavaLocalDateTimeMeta: Meta[java.time.LocalDateTime] =
    Basic.oneObject(Timestamp, Nil, classOf[java.time.LocalDateTime])

  /** @group Instances */
  implicit val JavaOffsetTimeMeta: Meta[java.time.OffsetTime] =
    Basic.oneObject(TimeWithTimezone, Nil, classOf[java.time.OffsetTime])

  // extra instances not in the spec

  /** @group Instances */
  implicit val JavaInstantMeta: Meta[java.time.Instant] =
    JavaOffsetDateTimeMeta.imap(_.toInstant)(_.atOffset(java.time.ZoneOffset.UTC))

  /** @group Instances */
  implicit val JavaTimeZoneId: Meta[java.time.ZoneId] = {
    def parse(str: String) =
      try {
        Right(java.time.ZoneId.of(str))
      } catch {
        case e: java.time.DateTimeException => Left(e.getMessage)
      }

    Meta.StringMeta.tiemap(parse(_))(_.getId)
  }
}
