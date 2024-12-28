// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.instances

import doobie.util.meta.Meta

import java.util.Map as JMap
import scala.jdk.CollectionConverters.*

object map {

  /** HSTORE maps to a java.util.Map[String, String]. */
  implicit val hstoreMetaJava: Meta[JMap[String, String]] =
    Meta.Advanced.other[JMap[String, String]]("hstore")

  /** HSTORE maps to a Map[String, String]. */
  implicit val hstoreMeta: Meta[Map[String, String]] =
    hstoreMetaJava.timap[Map[String, String]](_.asScala.toMap)(_.asJava)
}
