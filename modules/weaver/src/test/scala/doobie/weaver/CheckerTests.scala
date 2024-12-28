// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.weaver

import cats.effect.IO
import cats.effect.kernel.Resource
import doobie.syntax.string.*
import doobie.util.Read
import doobie.util.transactor.Transactor
import weaver.IOSuite

object CheckerTests extends IOSuite with IOChecker {

  override type Res = Transactor[IO]
  override def sharedResource: Resource[IO, Res] = doobie.h2.inMemory("queryspec")

  test("trivial") { implicit transactor =>
    check(sql"select 1".query[Int])
  }

  test("fail") { implicit transactor =>
    check(sql"select 1".query[String]).map(expectation =>
      expectation.xor(success),
    )
  }

  final case class Foo(x: Int)
  object Foo {
    implicit val read: Read[Foo] = Read.derived
  }

  test("trivial case-class") { implicit transactor =>
    check(sql"select 1".query[Foo])
  }

}
