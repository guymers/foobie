// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.instances

import doobie.util.meta.Meta
import org.postgresql.util.PGInterval

object interval {

  // Interval Type
  // There is no natural mapping to java.time types (https://github.com/tpolecat/doobie/pull/315)
  // so we provide the bare mapping and leave it at that.
  implicit val PGIntervalType: Meta[PGInterval] = Meta.Advanced.other[PGInterval]("interval")
}
