// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.util.generators

import zio.test.Gen

import java.time.*

// https://www.postgresql.org/docs/10/datatype-datetime.html
object TimeGenerators {

  val MinDate = LocalDate.of(-4712, 12, 31)
  val MaxDate = LocalDate.of(5874897, 12, 31)

  val MinTimestampDate = LocalDate.of(-4712, 12, 31)
  val MaxTimestampDate = LocalDate.of(294276, 12, 30) // use 30 days to avoid needing to care about offsets

  // resolution is 1 microsecond
  private def micros(nanos: Long) = Math.floorDiv(nanos, 1000)

  // Java min/max is 18, Postgres is 15:59
  private val MaxOffsetSeconds = 16 * 60 * 60 - 1
  val MinOffset = ZoneOffset.ofTotalSeconds(-MaxOffsetSeconds)
  val MaxOffset = ZoneOffset.ofTotalSeconds(MaxOffsetSeconds)

  // 4713 BC to 5874897 AD
  val genLocalDate: Gen[Any, LocalDate] = Gen.localDate(MinDate, MaxDate)

  val genLocalDateArray: Gen[Any, LocalDate] = Gen.localDate(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31))

  // 00:00:00.000000 to 23:59:59.999999
  val genLocalTime: Gen[Any, LocalTime] = {
    val min = micros(LocalTime.MIN.toNanoOfDay)
    val max = micros(LocalTime.MAX.toNanoOfDay)
    Gen.long(min, max).map(micros => LocalTime.ofNanoOfDay(micros * 1000))
  }

  val genLocalDateTime: Gen[Any, LocalDateTime] = for {
    date <- Gen.localDate(MinTimestampDate, MaxTimestampDate)
    time <- genLocalTime
  } yield LocalDateTime.of(date, time)

  val genLocalDateTimeArray: Gen[Any, LocalDateTime] = for {
    date <- genLocalDateArray
    time <- genLocalTime
  } yield LocalDateTime.of(date, time)

  val genInstant: Gen[Any, Instant] = {
    genLocalDateTime.map(_.toInstant(ZoneOffset.UTC))
  }

  val genZoneOffset: Gen[Any, ZoneOffset] = {
    Gen.int(MinOffset.getTotalSeconds, MaxOffset.getTotalSeconds).map(ZoneOffset.ofTotalSeconds(_))
  }

  val genOffsetDateTime: Gen[Any, OffsetDateTime] = for {
    dateTime <- genLocalDateTime
    offset <- genZoneOffset
  } yield dateTime.atOffset(offset)

  // 00:00:00+1559 to 24:00:00-1559
  val genOffsetTime: Gen[Any, OffsetTime] = for {
    time <- genLocalTime
    offset <- genZoneOffset
  } yield OffsetTime.of(time, offset)
}
