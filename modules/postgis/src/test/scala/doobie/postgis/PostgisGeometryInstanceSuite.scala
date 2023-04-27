// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgis

import doobie.postgres.PostgresTypesSuite
import doobie.util.Get
import doobie.util.Put
import net.postgis.jdbc.geometry.*
import zio.test.Gen

object PostgisGeometryInstanceSuite extends PostgisDatabaseSpec {
  import doobie.postgis.instances.geometry.*

  private val genPoint = for {
    x <- Gen.double
    y <- Gen.double
  } yield new Point(x, y)

  private val genLineString = for {
    points <- Gen.listOfN(3)(genPoint)
  } yield new LineString(points.toArray)

  private val genLinearRing = for {
    point <- genPoint
    points <- Gen.listOfN(3)(genPoint)
  } yield new LinearRing(point +: points.toArray :+ point)

  private val genPolygon = for {
    ring <- genLinearRing
  } yield new Polygon(Array(ring))

  private val genGeometryCollection = for {
    point <- genPoint
    lineString <- genLineString
  } yield new GeometryCollection(Array(point, lineString))

  override val spec = suite("PostgisGeometryInstances")(
    suiteGetPut[Geometry](genPoint),
    suiteGetPut[ComposedGeom](genLineString.map(ls => new MultiLineString(Array(ls)))),
    suiteGetPut[GeometryCollection](genGeometryCollection),
    suiteGetPut[LineString](genLineString),
    suiteGetPut[MultiLineString](Gen.listOfN(3)(genLineString).map(_.toArray).map(new MultiLineString(_))),
    suiteGetPut[MultiPoint](Gen.listOfN(3)(genPoint).map(_.toArray).map(new MultiPoint(_))),
    suiteGetPut[MultiPolygon](Gen.listOfN(3)(genPolygon).map(_.toArray).map(new MultiPolygon(_))),
    suiteGetPut[Point](genPoint),
    suiteGetPut[PointComposedGeom](genLineString),
    suiteGetPut[Polygon](genPolygon),
  )

  // All these types map to `geometry`
  private def suiteGetPut[A <: Geometry: Get: Put](gen: Gen[Any, A]) = PostgresTypesSuite.suiteGetPut[A]("geometry", gen)
}
