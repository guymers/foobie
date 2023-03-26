// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.instances

import doobie.util.invariant.*
import doobie.util.meta.Meta

import scala.reflect.ClassTag

object array extends ArrayInstances
trait ArrayInstances {

  // java.sql.Array::getArray returns an Object that may be of primitive type or of boxed type,
  // depending on the driver, so we can't really abstract over it. Also there's no telling what
  // happens with multi-dimensional arrays since most databases don't support them. So anyway here
  // we go with PostgreSQL support:
  //
  // PostgreSQL arrays show up as Array[AnyRef] with `null` for NULL, so that's mostly sensible;
  // there would be no way to distinguish 0 from NULL otherwise for an int[], for example. So,
  // these arrays can be multi-dimensional and can have NULL cells, but cannot have NULL slices;
  // i.e., {{1,2,3}, {4,5,NULL}} is ok but {{1,2,3}, NULL} is not. So this means we only have to
  // worry about Array[Array[...[A]]] and Array[Array[...[Option[A]]]] in our mappings.

  // Construct a pair of Meta instances for arrays of lifted (nullable) and unlifted (non-
  // nullable) reference types (as noted above, PostgreSQL doesn't ship arrays of primitives). The
  // automatic lifting to Meta will give us lifted and unlifted arrays, for a total of four variants
  // of each 1-d array type. In the non-nullable case we simply check for nulls and perform a cast;
  // in the nullable case we must copy the array in both directions to lift/unlift Option.
  @SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.Throw"))
  private def boxedPair[A >: Null <: AnyRef: ClassTag](
    elemType: String,
    arrayType: String,
    arrayTypeT: String*,
  ): (Meta[Array[A]], Meta[Array[Option[A]]]) = {
    val raw = Meta.Advanced.array[A](elemType, arrayType, arrayTypeT*)
    // Ensure `a`, which may be null, which is ok, contains no null elements.
    def checkNull[B >: Null](a: Array[B], e: Exception): Array[B] =
      if (a == null) null else if (a.exists(_ == null)) throw e else a
    (
      raw.timap(checkNull(_, NullableCellRead))(checkNull(_, NullableCellUpdate)),
      raw.timap[Array[Option[A]]](_.map(Option(_)))(_.map(_.orNull).toArray),
    )
  }

  // Arrays of lifted (nullable) and unlifted (non-nullable) Java wrapped primitives. PostgreSQL
  // does not seem to support tinyint[](use a bytea instead) and smallint[] always arrives as Int[]
  // so you can xmap if you need Short[]. The type names provided here are what is reported by JDBC
  // when metadata is requested; there are numerous aliases but these are the ones we need. Nothing
  // about this is portable, sorry. (╯°□°）╯︵ ┻━┻

  private val boxedPairBoolean = boxedPair[java.lang.Boolean]("bit", "_bit")
  implicit val unliftedBooleanArrayType: Meta[Array[java.lang.Boolean]] = boxedPairBoolean._1
  implicit val liftedBooleanArrayType: Meta[Array[Option[java.lang.Boolean]]] = boxedPairBoolean._2

  private val boxedPairInteger = boxedPair[java.lang.Integer]("int4", "_int4")
  implicit val unliftedIntegerArrayType: Meta[Array[java.lang.Integer]] = boxedPairInteger._1
  implicit val liftedIntegerArrayType: Meta[Array[Option[java.lang.Integer]]] = boxedPairInteger._2

  private val boxedPairLong = boxedPair[java.lang.Long]("int8", "_int8")
  implicit val unliftedLongArrayType: Meta[Array[java.lang.Long]] = boxedPairLong._1
  implicit val liftedLongArrayType: Meta[Array[Option[java.lang.Long]]] = boxedPairLong._2

  private val boxedPairFloat = boxedPair[java.lang.Float]("float4", "_float4")
  implicit val unliftedFloatArrayType: Meta[Array[java.lang.Float]] = boxedPairFloat._1
  implicit val liftedFloatArrayType: Meta[Array[Option[java.lang.Float]]] = boxedPairFloat._2

  private val boxedPairDouble = boxedPair[java.lang.Double]("float8", "_float8")
  implicit val unliftedDoubleArrayType: Meta[Array[java.lang.Double]] = boxedPairDouble._1
  implicit val liftedDoubleArrayType: Meta[Array[Option[java.lang.Double]]] = boxedPairDouble._2

  private val boxedPairString = boxedPair[java.lang.String]("varchar", "_varchar", "_char", "_text", "_bpchar")
  implicit val unliftedStringArrayType: Meta[Array[java.lang.String]] = boxedPairString._1
  implicit val liftedStringArrayType: Meta[Array[Option[java.lang.String]]] = boxedPairString._2

  private val boxedPairUUID = boxedPair[java.util.UUID]("uuid", "_uuid")
  implicit val unliftedUUIDArrayType: Meta[Array[java.util.UUID]] = boxedPairUUID._1
  implicit val liftedUUIDArrayType: Meta[Array[Option[java.util.UUID]]] = boxedPairUUID._2

  private val boxedPairBigDecimal = boxedPair[java.math.BigDecimal]("numeric", "_decimal", "_numeric")
  implicit val unliftedBigDecimalArrayType: Meta[Array[java.math.BigDecimal]] = boxedPairBigDecimal._1
  implicit val iftedBigDecimalArrayType: Meta[Array[Option[java.math.BigDecimal]]] = boxedPairBigDecimal._2

  // Unboxed equivalents (actually identical in the lifted case). We require that B is the unboxed
  // equivalent of A, otherwise this will fail in spectacular fashion, and we're using a cast in the
  // lifted case because the representation is identical, assuming no nulls. In the long run this
  // may need to become something slower but safer. Unclear.
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Null"))
  private def unboxedPair[A >: Null <: AnyRef: ClassTag, B <: AnyVal: ClassTag](f: A => B, g: B => A)(
    implicit
    boxed: Meta[Array[A]],
    boxedLifted: Meta[Array[Option[A]]],
  ): (Meta[Array[B]], Meta[Array[Option[B]]]) =
    // TODO: assert, somehow, that A is the boxed version of B so we catch errors on instance
    // construction, which is somewhat better than at [logical] execution time.
    (
      boxed.timap(a => if (a == null) null else a.map(f))(a => if (a == null) null else a.map(g)),
      boxedLifted.timap(_.asInstanceOf[Array[Option[B]]])(_.asInstanceOf[Array[Option[A]]]),
    )

  // Arrays of lifted (nullable) and unlifted (non-nullable) AnyVals
  private val unboxedPairBoolean = unboxedPair[java.lang.Boolean, Boolean](_.booleanValue, java.lang.Boolean.valueOf)
  implicit val unliftedUnboxedBooleanArrayType: Meta[Array[Boolean]] = unboxedPairBoolean._1
  implicit val liftedUnboxedBooleanArrayType: Meta[Array[Option[Boolean]]] = unboxedPairBoolean._2

  private val unboxedPairInteger = unboxedPair[java.lang.Integer, Int](_.intValue, java.lang.Integer.valueOf)
  implicit val unliftedUnboxedIntegerArrayType: Meta[Array[Int]] = unboxedPairInteger._1
  implicit val liftedUnboxedIntegerArrayType: Meta[Array[Option[Int]]] = unboxedPairInteger._2

  private val unboxedPairLong = unboxedPair[java.lang.Long, Long](_.longValue, java.lang.Long.valueOf)
  implicit val unliftedUnboxedLongArrayType: Meta[Array[Long]] = unboxedPairLong._1
  implicit val liftedUnboxedLongArrayType: Meta[Array[Option[Long]]] = unboxedPairLong._2

  private val unboxedPairFloat = unboxedPair[java.lang.Float, Float](_.floatValue, java.lang.Float.valueOf)
  implicit val unliftedUnboxedFloatArrayType: Meta[Array[Float]] = unboxedPairFloat._1
  implicit val liftedUnboxedFloatArrayType: Meta[Array[Option[Float]]] = unboxedPairFloat._2

  private val unboxedPairDouble = unboxedPair[java.lang.Double, Double](_.doubleValue, java.lang.Double.valueOf)
  implicit val unliftedUnboxedDoubleArrayType: Meta[Array[Double]] = unboxedPairDouble._1
  implicit val liftedUnboxedDoubleArrayType: Meta[Array[Option[Double]]] = unboxedPairDouble._2

  // Arrays of scala.BigDecimal - special case as BigDecimal can be null
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  implicit val bigDecimalMeta: Meta[Array[BigDecimal]] =
    Meta[Array[java.math.BigDecimal]].timap(
      _.map(a => if (a == null) null else BigDecimal.apply(a)),
    )(
      _.map(a => if (a == null) null else a.bigDecimal),
    )

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  implicit val optionBigDecimalMeta: Meta[Array[Option[BigDecimal]]] =
    Meta[Array[Option[java.math.BigDecimal]]].timap(
      _.map(_.map(a => if (a == null) null else BigDecimal.apply(a))),
    )(
      _.map(_.map(a => if (a == null) null else a.bigDecimal)),
    )

  // So, it turns out that arrays of structs don't work because something is missing from the
  // implementation. So this means we will only be able to support primitive types for arrays.
  //
  // java.sql.SQLFeatureNotSupportedException: Method org.postgresql.jdbc4.Jdbc4Array.getArrayImpl(long,int,Map) is not yet implemented.
  //   at org.postgresql.Driver.notImplemented(Driver.java:729)
  //   at org.postgresql.jdbc2.AbstractJdbc2Array.buildArray(AbstractJdbc2Array.java:771)
  //   at org.postgresql.jdbc2.AbstractJdbc2Array.getArrayImpl(AbstractJdbc2Array.java:171)
  //   at org.postgresql.jdbc2.AbstractJdbc2Array.getArray(AbstractJdbc2Array.java:128)

  // TODO: multidimensional arrays; in the worst case it's just copy/paste of everything above but
  // we can certainly do better than that.

}
