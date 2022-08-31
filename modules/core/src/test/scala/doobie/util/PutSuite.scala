// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.effect.IO
import doobie.Transactor

class PutSuite extends munit.FunSuite with PutSuitePlatform {
  case class X(x: Int)
  case class Q(x: String)

  case class Z(i: Int, s: String)
  object S

  case class Reg1(x: Int)
  case class Reg2(x: Int)

  val xa = Transactor.fromDriverManager[IO](
    "org.h2.Driver",
    "jdbc:h2:mem:queryspec;DB_CLOSE_DELAY=-1",
    "sa",
    "",
  )

  case class Foo(s: String)
  case class Bar(n: Int)

  test("Put should exist for primitive types") {
    Put[Int]: Unit
    Put[String]: Unit
  }

  test("Put should be auto derived for unary products") {
    import doobie.generic.auto.*

    Put[X]: Unit
    Put[Q]: Unit
  }

  test("Put is not auto derived without an import") {
    val _ = compileErrors("Put[X]")
    val _ = compileErrors("Put[Q]")
  }

  test("Put can be manually derived for unary products") {
    Put.derived[X]: Unit
    Put.derived[Q]: Unit
  }

  test("Put should not be derived for non-unary products") {
    import doobie.generic.auto.*

    val _ = compileErrors("Put[Z]")
    val _ = compileErrors("Put[(Int, Int)]")
    val _ = compileErrors("Put[S.type]")
  }

}
