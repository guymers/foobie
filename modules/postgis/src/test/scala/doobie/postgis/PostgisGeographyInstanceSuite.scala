// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgis

import net.postgis.jdbc.geometry.*
import zio.test.Gen

object PostgisGeographyInstanceSuite extends PostgisDatabaseSpec {
  import doobie.postgis.instances.geography.*
  import doobie.postgres.PostgresTypesSuite.suiteGetPut

  private val genPoint = for {
    x <- Gen.double
    y <- Gen.double
  } yield {
    val p = new Point(x, y)
    p.setSrid(4326)
    p
  }

  private val genLineString = for {
    points <- Gen.listOfN(3)(genPoint)
  } yield {
    val ls = new LineString(points.toArray)
    ls.setSrid(4326)
    ls
  }

  private val genLinearRing = for {
    point <- genPoint
    points <- Gen.listOfN(3)(genPoint)
  } yield new LinearRing(point +: points.toArray :+ point)

  private val genPolygon = for {
    ring <- genLinearRing
  } yield {
    val p = new Polygon(Array(ring))
    p.setSrid(4326)
    p
  }

  override val spec = suite("PostgisGeometryInstances")(
    suiteGetPut("GEOGRAPHY(POINT)", genPoint),
    suiteGetPut("GEOGRAPHY(LINESTRING)", genLineString),
    suiteGetPut("GEOGRAPHY(POLYGON)", genPolygon),
  )
}
