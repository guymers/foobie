// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.util.meta.Meta

class WriteSuite extends munit.FunSuite with WriteSuitePlatform {

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

  test("Write should exist for some fancy types") {
    import doobie.generic.auto.*

    Write[Int]: Unit
    Write[(Int, Int)]: Unit
    Write[(Int, Int, String)]: Unit
    Write[(Int, (Int, String))]: Unit
  }

  test("Write is not auto derived without an import") {
    val _ = compileErrors("Write[(Int, Int)]")
    val _ = compileErrors("Write[(Int, Int, String)]")
    val _ = compileErrors("Write[(Int, (Int, String))]")
  }

  test("Write can be manually derived") {
    Write.derived[LenStr1]
  }

  test("Write should exist for Unit") {
    import doobie.generic.auto.*

    Write[Unit]: Unit
    assertEquals(Write[(Int, Unit)].length, 1)
  }

  test("Write should exist for option of some fancy types") {
    import doobie.generic.auto.*

    Write[Option[Int]]: Unit
    Write[Option[(Int, Int)]]: Unit
    Write[Option[(Int, Int, String)]]: Unit
    Write[Option[(Int, (Int, String))]]: Unit
    Write[Option[(Int, Option[(Int, String)])]]: Unit
  }

  test("Write auto derives nested types") {
    import doobie.generic.auto.*

    assertEquals(Write[Widget].length, 3)
  }

  test("Write does not auto derive nested types without an import") {
    val _ = compileErrors("Write.derived[Widget]")
  }

  test("Write should exist for option of Unit") {
    import doobie.generic.auto.*

    assertEquals(Write[Option[Unit]].length, 0)
    assertEquals(Write[Option[(Int, Unit)]].length, 1)
  }

  test("Write should select multi-column instance by default") {
    import doobie.generic.auto.*

    assertEquals(Write[LenStr1].length, 2)
  }

  test("Write should select 1-column instance when available") {
    assertEquals(Write[LenStr2].length, 1)
  }

}
