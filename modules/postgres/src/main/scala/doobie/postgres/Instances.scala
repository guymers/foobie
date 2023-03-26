// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import doobie.util.meta.Meta
import org.postgresql.util.*

import java.net.InetAddress
import java.util.Map as JMap
import java.util.UUID
import scala.jdk.CollectionConverters.*

trait Instances
  extends instances.ArrayInstances
  with instances.EnumerationInstances
  with instances.GeometricInstances {

  // N.B. `Meta` is the lowest-level mapping and must always cope with NULL. Easy to forget.

  // PSQLException: : Bad value for type double : 1,234.56  (AbstractJdbc2ResultSet.java:3059)
  //   org.postgresql.jdbc2.AbstractJdbc2ResultSet.toDouble(AbstractJdbc2ResultSet.java:3059)
  //   org.postgresql.jdbc2.AbstractJdbc2ResultSet.getDouble(AbstractJdbc2ResultSet.java:2383)
  //   org.postgresql.jdbc2.AbstractJdbc2ResultSet.internalGetObject(AbstractJdbc2ResultSet.java:152)
  //   org.postgresql.jdbc3.AbstractJdbc3ResultSet.internalGetObject(AbstractJdbc3ResultSet.java:36)
  //   org.postgresql.jdbc4.AbstractJdbc4ResultSet.internalGetObject(AbstractJdbc4ResultSet.java:300)
  //   org.postgresql.jdbc2.AbstractJdbc2ResultSet.getObject(AbstractJdbc2ResultSet.java:2704)

  // Interval Type
  // There is no natural mapping to java.time types (https://github.com/tpolecat/doobie/pull/315)
  // so we provide the bare mapping and leave it at that.
  implicit val PGIntervalType: Meta[PGInterval] = Meta.Advanced.other[PGInterval]("interval")

  // UUID
  implicit val UuidType: Meta[UUID] = Meta.Advanced.other[UUID]("uuid")

  // Network Address Types
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  implicit val InetType: Meta[InetAddress] = Meta.Advanced.other[PGobject]("inet").timap[InetAddress](o =>
    Option(o).map(a => InetAddress.getByName(a.getValue)).orNull,
  )(a =>
    Option(a).map { a =>
      val o = new PGobject
      o.setType("inet")
      o.setValue(a.getHostAddress)
      o
    }.orNull,
  )

  /** HSTORE maps to a java.util.Map[String, String]. */
  implicit val hstoreMetaJava: Meta[JMap[String, String]] =
    Meta.Advanced.other[JMap[String, String]]("hstore")

  /** HSTORE maps to a Map[String, String]. */
  implicit val hstoreMeta: Meta[Map[String, String]] =
    hstoreMetaJava.timap[Map[String, String]](_.asScala.toMap)(_.asJava)

}
