// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgis

import doobie.postgres.PostgresDatabaseSpec
import net.postgis.jdbc.geometry.*
import zio.test.Gen

object PostgisGeographyInstanceSuite extends PostgresDatabaseSpec {
  import doobie.postgis.instances.geography.*
  import doobie.postgres.PostgresTypesSuite.suiteGetPut

  private def createPoint(lat: Double, lon: Double): Point = {
    val p = new Point(lon, lat)
    p.setSrid(4326)
    p
  }
  private val point1 = createPoint(1, 2)
  private val point2 = createPoint(1, 3)
  private val lineString = {
    val ls = new LineString(Array[Point](point1, point2))
    ls.setSrid(4326)
    ls
  }

  override val spec = suite("PostgisGeometryInstances")(
    suiteGetPut("GEOGRAPHY(POINT)", Gen.const(point1)),
    suiteGetPut("GEOGRAPHY(LINESTRING)", Gen.const(lineString)),
  )
}
