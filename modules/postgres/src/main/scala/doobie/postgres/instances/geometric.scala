package doobie.postgres.instances

import doobie.util.meta.Meta
import org.postgresql.geometric.*

object geometric {

  // Geometric Types, minus PGline which is "not fully implemented"
  implicit val PGboxType: Meta[PGbox] = Meta.Advanced.other[PGbox]("box")
  implicit val PGcircleType: Meta[PGcircle] = Meta.Advanced.other[PGcircle]("circle")
  implicit val PGlsegType: Meta[PGlseg] = Meta.Advanced.other[PGlseg]("lseg")
  implicit val PGpathType: Meta[PGpath] = Meta.Advanced.other[PGpath]("path")
  implicit val PGpointType: Meta[PGpoint] = Meta.Advanced.other[PGpoint]("point")
  implicit val PGpolygonType: Meta[PGpolygon] = Meta.Advanced.other[PGpolygon]("polygon")
}
