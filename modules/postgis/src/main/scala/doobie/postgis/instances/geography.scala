// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgis.instances

import doobie.util.invariant.*
import doobie.util.meta.Meta
import net.postgis.jdbc.PGgeography
import net.postgis.jdbc.geometry.*
import org.tpolecat.typename.*

import scala.reflect.ClassTag

object geography {

  implicit val PGgeographyType: Meta[PGgeography] = Meta.Advanced.other[PGgeography]("geography")

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Throw"))
  private def geographyType[A >: Null <: Geometry: TypeName](implicit A: ClassTag[A]): Meta[A] =
    PGgeographyType.timap[A](g =>
      try A.runtimeClass.cast(g.getGeometry).asInstanceOf[A]
      catch {
        case _: ClassCastException => throw InvalidObjectMapping(A.runtimeClass, g.getGeometry.getClass)
      },
    )(new PGgeography(_))

  implicit val MultiLineStringType: Meta[MultiLineString] = geographyType[MultiLineString]
  implicit val MultiPolygonType: Meta[MultiPolygon] = geographyType[MultiPolygon]
  implicit val PointComposedGeomType: Meta[PointComposedGeom] = geographyType[PointComposedGeom]
  implicit val LineStringType: Meta[LineString] = geographyType[LineString]
  implicit val MultiPointType: Meta[MultiPoint] = geographyType[MultiPoint]
  implicit val PolygonType: Meta[Polygon] = geographyType[Polygon]
  implicit val PointType: Meta[Point] = geographyType[Point]
}
