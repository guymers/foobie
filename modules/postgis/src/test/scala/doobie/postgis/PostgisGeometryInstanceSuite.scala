// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgis

import doobie.postgres.PostgresDatabaseSpec
import doobie.postgres.PostgresTypesSuite
import doobie.util.Get
import doobie.util.Put
import net.postgis.jdbc.geometry.*
import zio.test.Gen

object PostgisGeometryInstanceSuite extends PostgresDatabaseSpec {
  import doobie.postgis.instances.geometry.*

  // TODO gen not random
  lazy val rnd: Iterator[Double] = LazyList.continually(scala.util.Random.nextDouble()).iterator
  lazy val pts: Iterator[Point] = LazyList.continually(new Point(rnd.next(), rnd.next())).iterator
  lazy val lss: Iterator[LineString] =
    LazyList.continually(new LineString(Array(pts.next(), pts.next(), pts.next()))).iterator
  lazy val lrs: Iterator[LinearRing] = LazyList.continually(new LinearRing({
    lazy val p = pts.next()
    Array(p, pts.next(), pts.next(), pts.next(), p)
  })).iterator
  lazy val pls: Iterator[Polygon] = LazyList.continually(new Polygon(lras.next())).iterator

  // Streams of arrays of random geometry values
  lazy val ptas: Iterator[Array[Point]] = LazyList.continually(Array(pts.next(), pts.next(), pts.next())).iterator
  lazy val plas: Iterator[Array[Polygon]] = LazyList.continually(Array(pls.next(), pls.next(), pls.next())).iterator
  lazy val lsas: Iterator[Array[LineString]] = LazyList.continually(Array(lss.next(), lss.next(), lss.next())).iterator
  lazy val lras: Iterator[Array[LinearRing]] = LazyList.continually(Array(lrs.next(), lrs.next(), lrs.next())).iterator

  override val spec = suite("PostgisGeometryInstances")(
    suiteGetPut[Geometry](pts.next()),
    suiteGetPut[ComposedGeom](new MultiLineString(lsas.next())),
    suiteGetPut[GeometryCollection](new GeometryCollection(Array(pts.next(), lss.next()))),
    suiteGetPut[MultiLineString](new MultiLineString(lsas.next())),
    suiteGetPut[MultiPolygon](new MultiPolygon(plas.next())),
    suiteGetPut[PointComposedGeom](lss.next()),
    suiteGetPut[LineString](lss.next()),
    suiteGetPut[MultiPoint](new MultiPoint(ptas.next())),
    suiteGetPut[Polygon](pls.next()),
    suiteGetPut[Point](pts.next()),
  )

  // All these types map to `geometry`
  private def suiteGetPut[A <: Geometry: Get: Put](a: A) = PostgresTypesSuite.suiteGetPut[A]("geometry", Gen.const(a))
}
