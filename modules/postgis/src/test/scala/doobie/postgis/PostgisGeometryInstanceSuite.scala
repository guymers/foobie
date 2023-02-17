// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgis

import doobie.postgis.instances.geometry.*
import doobie.postgres.PostgresInstanceCheckSuite
import doobie.util.meta.Meta
import net.postgis.jdbc.geometry.*

class PostgisGeometryInstanceSuite extends munit.ScalaCheckSuite with PostgresInstanceCheckSuite {

  lazy val rnd: Iterator[Double] = LazyList.continually(scala.util.Random.nextDouble()).iterator
  lazy val pts: Iterator[Point] = LazyList.continually(new Point(rnd.next(), rnd.next())).iterator
  lazy val lss: Iterator[LineString] =
    LazyList.continually(new LineString(Array(pts.next(), pts.next(), pts.next()))).iterator
  lazy val lrs: Iterator[LinearRing] = LazyList.continually(new LinearRing({
    lazy val p = pts.next();
    Array(p, pts.next(), pts.next(), pts.next(), p)
  })).iterator
  lazy val pls: Iterator[Polygon] = LazyList.continually(new Polygon(lras.next())).iterator

  // Streams of arrays of random geometry values
  lazy val ptas: Iterator[Array[Point]] = LazyList.continually(Array(pts.next(), pts.next(), pts.next())).iterator
  lazy val plas: Iterator[Array[Polygon]] = LazyList.continually(Array(pls.next(), pls.next(), pls.next())).iterator
  lazy val lsas: Iterator[Array[LineString]] = LazyList.continually(Array(lss.next(), lss.next(), lss.next())).iterator
  lazy val lras: Iterator[Array[LinearRing]] = LazyList.continually(Array(lrs.next(), lrs.next(), lrs.next())).iterator

  // All these types map to `geometry`
  def testInOutGeom[A <: Geometry: Meta](a: A) =
    testInOut[A]("geometry", a)

  testInOutGeom[Geometry](pts.next())
  testInOutGeom[ComposedGeom](new MultiLineString(lsas.next()))
  testInOutGeom[GeometryCollection](new GeometryCollection(Array(pts.next(), lss.next())))
  testInOutGeom[MultiLineString](new MultiLineString(lsas.next()))
  testInOutGeom[MultiPolygon](new MultiPolygon(plas.next()))
  testInOutGeom[PointComposedGeom](lss.next())
  testInOutGeom[LineString](lss.next())
  testInOutGeom[MultiPoint](new MultiPoint(ptas.next()))
  testInOutGeom[Polygon](pls.next())
  testInOutGeom[Point](pts.next())
}
