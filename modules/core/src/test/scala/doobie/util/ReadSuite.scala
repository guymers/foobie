// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie
package util

import cats.effect.IO
import cats.syntax.apply.*
import doobie.syntax.connectionio.*
import doobie.syntax.string.*

object ReadSuite {

  case class X(x: Int) extends AnyVal

  case class LenStr1(n: Int, s: String)

  case class LenStr2(n: Int, s: String)
  object LenStr2 {
    implicit val meta: Meta[LenStr2] = Meta[String].timap(s => LenStr2(s.length, s))(_.s)
  }

  case class Nested(a: String, b: Option[NoneOptional], c: Boolean, d: Option[AllOptional])
  object Nested {
    implicit val read: Read[Nested] = Read.derived
  }

  case class NoneOptional(a: String, b: Int)
  object NoneOptional {
    implicit val read: Read[NoneOptional] = Read.derived
  }

  case class AllOptional(a: Option[String], b: Option[Int])
  object AllOptional {
    implicit val read: Read[AllOptional] = Read.derived
  }
}
class ReadSuite extends munit.FunSuite with ReadSuitePlatform {
  import ReadSuite.*
  import cats.effect.unsafe.implicits.global

  val xa = Transactor.fromDriverManager[IO](
    "org.h2.Driver",
    "jdbc:h2:mem:;DB_CLOSE_DELAY=-1",
    "sa",
    "",
  )

  test("Read should exist for some fancy types") {
    util.Read[Int]: Unit
    util.Read[(Int, Int)]: Unit
    util.Read[(Int, Int, String)]: Unit
    util.Read[(Int, (Int, String))]: Unit
  }

  test("Read should exist for Unit") {
    util.Read[Unit]: Unit
    assertEquals(util.Read[(Int, Unit)].length, 1)
  }

  test("Read should exist for option of some fancy types") {
    util.Read[Option[Int]]: Unit
    util.Read[Option[(Int, Int)]]: Unit
    util.Read[Option[(Int, Int, String)]]: Unit
    util.Read[Option[(Int, (Int, String))]]: Unit
    util.Read[Option[(Int, Option[(Int, String)])]]: Unit
  }

  test("Read should exist for option of Unit") {
    util.Read[Option[Unit]]: Unit
    assertEquals(util.Read[Option[(Int, Unit)]].length, 1)
  }

  test("Read should select multi-column instance by default") {
    assertEquals(util.Read[LenStr1].length, 2)
  }

  test("Read should select 1-column instance when available") {
    assertEquals(util.Read[LenStr2].length, 1)
  }

  case class Woozle(a: (String, Int), b: (Int, String), c: Boolean)

  test("Read should exist for some fancy types") {
    util.Read[Woozle]: Unit
    util.Read[(Woozle, String)]: Unit
    util.Read[(Int, (Woozle, Woozle, String))]: Unit
  }

  test("Read should exist for option of some fancy types") {
    util.Read[Option[Woozle]]: Unit
    util.Read[Option[(Woozle, String)]]: Unit
    util.Read[Option[(Int, (Woozle, Woozle, String))]]: Unit
  }

  test(".product should product the correct ordering of gets") {
    val readInt = util.Read[Int]
    val readString = util.Read[String]

    val p = readInt.product(readString)

    assertEquals(p.gets, (readInt.gets ++ readString.gets))
  }

  test("Read should select correct columns when combined with `ap`") {
    val r = util.Read[Int]
    val c = (r, r, r, r, r).tupled
    val q = sql"SELECT 1, 2, 3, 4, 5".query(c).to[List]
    val o = q.transact(xa).unsafeRunSync()

    assertEquals(o, List((1, 2, 3, 4, 5)))
  }

  test("Read should select correct columns when combined with `product`") {
    val r = util.Read[Int].product(util.Read[Int].product(util.Read[Int]))

    val q = sql"SELECT 1, 2, 3".query(r).to[List]
    val o = q.transact(xa).unsafeRunSync()

    assertEquals(o, List((1, (2, 3))))
  }

  test("handles optional nested types") {
    val a = sql"SELECT 'a', 'a1', 1, false, 'a2', 2".query[Nested].unique.transact(xa).unsafeRunSync()
    assertEquals(a, Nested("a", Some(NoneOptional("a1", 1)), false, Some(AllOptional(Some("a2"), Some(2)))))

    val b = sql"SELECT 'a', 'a1', 1, false, NULL, NULL".query[Nested].unique.transact(xa).unsafeRunSync()
    assertEquals(b, Nested("a", Some(NoneOptional("a1", 1)), false, None))

    val c = sql"SELECT 'a', NULL, 1, false, NULL, NULL".query[Nested].unique.transact(xa).unsafeRunSync()
    assertEquals(c, Nested("a", None, false, None))

    val d = sql"SELECT 'a', 'a1', NULL, false, NULL, 2".query[Nested].unique.transact(xa).unsafeRunSync()
    assertEquals(d, Nested("a", None, false, Some(AllOptional(None, Some(2)))))
  }

}
