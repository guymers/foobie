// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.mysql.util.generators

import zio.test.Gen

import java.time.*

// https://dev.mysql.com/doc/refman/5.7/en/datetime.html
object TimeGenerators {

  // max resolution is 1 microsecond
  private def micros(nanos: Long) = Math.floorDiv(nanos, 1000)

  // 1000-01-01 to 9999-12-31
  val genLocalDate: Gen[Any, LocalDate] = {
    Gen.localDate(LocalDate.of(1000, 1, 1), LocalDate.of(9999, 12, 31))
  }

  // 00:00:00.000000 to 23:59:59.999999
  val genLocalTime: Gen[Any, LocalTime] = {
    val min = micros(LocalTime.MIN.toNanoOfDay)
    val max = micros(LocalTime.MAX.toNanoOfDay)
    Gen.long(min, max).map(micros => LocalTime.ofNanoOfDay(micros * 1000))
  }

  // '1000-01-01 00:00:00.000000' to '9999-12-31 23:59:59.999999'
  val genLocalDateTime: Gen[Any, LocalDateTime] = for {
    date <- genLocalDate
    time <- genLocalTime
  } yield LocalDateTime.of(date, time)

  // '1970-01-01 00:00:01.000000' to '2038-01-19 03:14:07.999999
  val genInstant: Gen[Any, Instant] = {
    val min = 1 * 1000000L + 0
    val max = 2147483647 * 1000000L + 999999

    Gen.long(min, max).map { micros =>
      Instant.ofEpochSecond(micros / 1000000, micros % 1000000 * 1000)
    }
  }

  val genOffsetDateTime: Gen[Any, OffsetDateTime] = for {
    instant <- genInstant
    offset <- Gen.zoneOffset
  } yield instant.atOffset(offset)

}
