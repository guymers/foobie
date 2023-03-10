// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.weaver

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.apply.*
import doobie.syntax.string.*
import doobie.util.Read
import doobie.util.transactor.Transactor
import weaver.*

object CheckerTests extends IOSuite with IOChecker {

  override type Res = Transactor[IO]
  override def sharedResource: Resource[IO, Res] =
    Resource.pure(Transactor.fromDriverManager[IO](
      "org.h2.Driver",
      "jdbc:h2:mem:queryspec;DB_CLOSE_DELAY=-1",
      "sa",
      "",
    ))

  test("trivial") { implicit transactor =>
    check(sql"select 1".query[Int])
  }

  test("fail") { implicit transactor =>
    check(sql"select 1".query[String]).map(expectation =>
      expectation.xor(success),
    )
  }

  final case class Foo[F[_]](x: Int)

  test("trivial case-class") { implicit transactor =>
    import doobie.util.Read.Auto.*

    check(sql"select 1".query[Foo[cats.Id]])
  }

  test("Read should select correct columns when combined with `product`") { implicit transactor =>
    val ri = Read[Int]
    val rs = Read[String]

    // tupled use product under the hood
    val combined: Read[(Int, String)] = (ri, rs).tupled

    check(sql"SELECT 1, '2'".query(combined))
  }

  test("Read should select correct columns for checking when combined with `ap`") { implicit transactor =>
    import doobie.util.Read.Auto.*

    val readInt = Read[(Int, Int)]
    val readIntToInt: Read[Tuple2[Int, Int] => String] =
      Read[(String, String)].map(i => k => s"$i,$k")

    val combined: Read[String] = readInt.ap(readIntToInt)

    check(sql"SELECT '1', '2', 3, 4".query(combined))
  }

}
