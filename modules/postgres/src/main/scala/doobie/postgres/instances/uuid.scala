// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.instances

import doobie.util.meta.Meta

import java.util.UUID

object uuid {

  implicit val UuidType: Meta[UUID] = Meta.Advanced.other[UUID]("uuid")
}
