// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.effect.IO
import cats.syntax.apply.*
import cats.syntax.semigroupal.*
import doobie.syntax.connectionio.*
import doobie.syntax.string.*
import doobie.util.meta.Meta
import doobie.util.transactor.Transactor

class ReadSuite extends munit.FunSuite with ReadSuitePlatform {

  import cats.effect.unsafe.implicits.global

  case class LenStr1(n: Int, s: String)

  case class LenStr2(n: Int, s: String)
  object LenStr2 {
    implicit val LenStrMeta: Meta[LenStr2] =
      Meta[String].timap(s => LenStr2(s.length, s))(_.s)
  }

  case class Widget(n: Int, w: Widget.Inner)
  object Widget {
    case class Inner(n: Int, s: String)
  }

  val xa = Transactor.fromDriverManager[IO](
    "org.h2.Driver",
    "jdbc:h2:mem:;DB_CLOSE_DELAY=-1",
    "sa",
    "",
  )

  test("Read should exist for some fancy types") {
    import doobie.generic.auto.*

    Read[Int]: Unit
    Read[(Int, Int)]: Unit
    Read[(Int, Int, String)]: Unit
    Read[(Int, (Int, String))]: Unit
  }

  test("Read is not auto derived without an import") {
    val _ = compileErrors("Read[(Int, Int)]")
    val _ = compileErrors("Read[(Int, Int, String)]")
    val _ = compileErrors("Read[(Int, (Int, String))]")
  }

  test("Read auto derives nested types") {
    import doobie.generic.auto.*

    assertEquals(Read[Widget].length, 3)
  }

  test("Read does not auto derive nested types without an import") {
    val _ = compileErrors("Read.derived[Widget]")
  }

  test("Read can be manually derived") {
    Read.derived[LenStr1]
  }

  test("Read should exist for Unit") {
    import doobie.generic.auto.*

    assertEquals(Read[Unit].length, 0)
    assertEquals(Read[(Int, Unit)].length, 1)
  }

  test("Read should exist for option of some fancy types") {
    import doobie.generic.auto.*

    Read[Option[Int]]: Unit
    Read[Option[(Int, Int)]]: Unit
    Read[Option[(Int, Int, String)]]: Unit
    Read[Option[(Int, (Int, String))]]: Unit
    Read[Option[(Int, Option[(Int, String)])]]: Unit
  }

  test("Read should exist for option of Unit") {
    import doobie.generic.auto.*

    assertEquals(Read[Option[Unit]].length, 0)
    assertEquals(Read[Option[(Int, Unit)]].length, 1)
  }

  test("Read should select multi-column instance by default") {
    import doobie.generic.auto.*

    assertEquals(Read[LenStr1].length, 2)
  }

  test("Read should select 1-column instance when available") {
    assertEquals(Read[LenStr2].length, 1)
  }

  test(".product should product the correct ordering of gets") {
    val readInt = Read[Int]
    val readString = Read[String]

    val p = readInt.product(readString)

    assertEquals(p.gets, readInt.gets ++ readString.gets)
  }

  test("Read should select correct columns when combined with `ap`") {
    val r = Read[Int]
    val c = (r, r, r, r, r).tupled

    val q = sql"SELECT 1, 2, 3, 4, 5".query(c).to[List]
    val o = q.transact(xa).unsafeRunSync()

    assertEquals(o, List((1, 2, 3, 4, 5)))
  }

  test("Read should select correct columns when combined with `product`") {
    val r = Read[Int].product(Read[Int].product(Read[Int]))

    val q = sql"SELECT 1, 2, 3".query(r).to[List]
    val o = q.transact(xa).unsafeRunSync()

    assertEquals(o, List((1, (2, 3))))
  }

}
