// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie
package util

object WriteSuite {

  case class X(x: Int) extends AnyVal

  case class LenStr1(n: Int, s: String)

  case class LenStr2(n: Int, s: String)
  object LenStr2 {
    implicit val meta: Meta[LenStr2] = Meta[String].timap(s => LenStr2(s.length, s))(_.s)
  }
}
class WriteSuite extends munit.FunSuite with WriteSuitePlatform {
  import WriteSuite.*

  test("Write should exist for some fancy types") {
    util.Write[Int]: Unit
    util.Write[(Int, Int)]: Unit
    util.Write[(Int, Int, String)]: Unit
    util.Write[(Int, (Int, String))]: Unit
  }

  test("Write should exist for Unit") {
    util.Write[Unit]: Unit
    assertEquals(util.Write[(Int, Unit)].length, 1)
  }

  test("Write should exist for option of some fancy types") {
    util.Write[Option[Int]]: Unit
    util.Write[Option[(Int, Int)]]: Unit
    util.Write[Option[(Int, Int, String)]]: Unit
    util.Write[Option[(Int, (Int, String))]]: Unit
    util.Write[Option[(Int, Option[(Int, String)])]]: Unit
  }

  test("Write should exist for option of Unit") {
    util.Write[Option[Unit]]: Unit
    assertEquals(util.Write[Option[(Int, Unit)]].length, 1)
  }

  test("Write should select multi-column instance by default") {
    assertEquals(util.Write[LenStr1].length, 2)
  }

  test("Write should select 1-column instance when available") {
    assertEquals(util.Write[LenStr2].length, 1)
  }

  case class Woozle(a: (String, Int), b: (Int, String), c: Boolean)

  test("Write should exist for some fancy types") {
    util.Write[Woozle]: Unit
    util.Write[(Woozle, String)]: Unit
    util.Write[(Int, (Woozle, Woozle, String))]: Unit
  }

  test("Write should exist for option of some fancy types") {
    util.Write[Option[Woozle]]: Unit
    util.Write[Option[(Woozle, String)]]: Unit
    util.Write[Option[(Int, (Woozle, Woozle, String))]]: Unit
  }
}
