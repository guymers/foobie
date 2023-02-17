// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgis

import doobie.postgis.instances.geography.*
import doobie.postgres.PostgresInstanceCheckSuite
import net.postgis.jdbc.geometry.*

class PostgisGeographyInstanceSuite extends munit.ScalaCheckSuite with PostgresInstanceCheckSuite {

  def createPoint(lat: Double, lon: Double): Point = {
    val p = new Point(lon, lat)
    p.setSrid(4326)

    p
  }
  val point1 = createPoint(1, 2)
  val point2 = createPoint(1, 3)
  val lineString = new LineString(Array[Point](point1, point2))
  lineString.setSrid(4326)

  // test geography points
  testInOut("GEOGRAPHY(POINT)", point1)
  testInOut("GEOGRAPHY(LINESTRING)", lineString)
}
