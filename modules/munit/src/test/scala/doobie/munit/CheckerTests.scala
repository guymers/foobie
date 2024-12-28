// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.munit

import cats.effect.IO
import doobie.syntax.string.*
import doobie.util.Read
import doobie.util.transactor.Transactor
import munit.*

trait CheckerChecks[M[_]] extends FunSuite with Checker[M] {

  lazy val transactor = Transactor.fromDriverManager[M](
    "org.h2.Driver",
    "jdbc:h2:mem:queryspec;DB_CLOSE_DELAY=-1",
    "sa",
    "",
  )

  test("trivial") {
    check(sql"select 1".query[Int])
  }

  test("fail".fail) {
    check(sql"select 1".query[String])
  }

  test("trivial case-class") {
    check(sql"select 1".query[CheckerChecks.Foo])
  }

}

object CheckerChecks {
  final case class Foo(x: Int)
  object Foo {
    implicit val read: Read[Foo] = Read.derived
  }
}

class IOCheckerCheck extends CheckerChecks[IO] with IOChecker {}
