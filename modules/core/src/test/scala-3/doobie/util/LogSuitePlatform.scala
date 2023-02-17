// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.util.log.ProcessingFailure
import doobie.util.log.Success

trait LogSuitePlatform { self: LogSuite =>

  test("[Query] n-arg success") {
    val Sql = "select 1 where ? = ?"
    val Arg = 1 *: 1 *: EmptyTuple
    eventForUniqueQuery(Sql, Arg) match {
      case Success(Sql, List(1, 1), _, _) => ()
      case a => fail(s"no match: $a")
    }
  }

  test("[Query] n-arg processing failure") {
    val Sql = "select 1 where ? = ?"
    val Arg = 1 *: 2 *: EmptyTuple
    eventForUniqueQuery(Sql, Arg) match {
      case ProcessingFailure(Sql, List(1, 2), _, _, _) => ()
      case a => fail(s"no match: $a")
    }
  }

  test("[Update] n-arg success") {
    val Sql = "update foo set bar = ?"
    val Arg = 42 *: EmptyTuple
    eventForUniqueUpdate(Sql, Arg) match {
      case Success(Sql, List(42), _, _) => ()
      case a => fail(s"no match: $a")
    }
  }

}
