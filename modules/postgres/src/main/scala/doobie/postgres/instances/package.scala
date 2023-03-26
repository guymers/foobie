// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import cats.Show
import org.postgresql.util.PGobject

package object instances {

  implicit val showPGobject: Show[PGobject] = Show.show(_.getValue.take(250))
}
