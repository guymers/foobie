// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.util.generators

import zio.test.Gen

import java.sql.Date
import java.sql.Time
import java.sql.Timestamp

object SQLGenerators {

  val genSQLTime: Gen[Any, Time] = TimeGenerators.genLocalTime.map(Time.valueOf(_))
  val genSQLDate: Gen[Any, Date] = TimeGenerators.genLocalDate.map(Date.valueOf(_))
  val genSQLTimestamp: Gen[Any, Timestamp] = TimeGenerators.genLocalDateTime.map(Timestamp.valueOf(_))

}
