// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.util.meta.Meta

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

  case class Widget(n: Int, w: Widget.Inner)
  object Widget {
    case class Inner(n: Int, s: String)
  }

  test("Write should exist for some fancy types") {
    import doobie.util.Write.Auto.*

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
    import doobie.util.Write.Auto.*

    Write[Unit]: Unit
    assertEquals(Write[(Int, Unit)].length, 1)
  }

  test("Write should exist for option of some fancy types") {
    import doobie.util.Write.Auto.*

    Write[Option[Int]]: Unit
    Write[Option[(Int, Int)]]: Unit
    Write[Option[(Int, Int, String)]]: Unit
    Write[Option[(Int, (Int, String))]]: Unit
    Write[Option[(Int, Option[(Int, String)])]]: Unit
  }

  test("Write auto derives nested types") {
    import doobie.util.Write.Auto.*

    assertEquals(Write[Widget].length, 3)
  }

  test("Write does not auto derive nested types without an import") {
    val _ = compileErrors("Write.derived[Widget]")
  }

  test("Write should exist for option of Unit") {
    import doobie.util.Write.Auto.*

    assertEquals(Write[Option[Unit]].length, 0)
    assertEquals(Write[Option[(Int, Unit)]].length, 1)
  }

  test("Write should select multi-column instance by default") {
    import doobie.util.Write.Auto.*

    assertEquals(Write[LenStr1].length, 2)
  }

  test("Write should select 1-column instance when available") {
    assertEquals(Write[LenStr2].length, 1)
  }

  case class Woozle(a: (String, Int), b: (Int, String), c: Boolean)

  test("Write should exist for some fancy types") {
    import doobie.util.Write.Auto.*

    Write[Woozle]: Unit
    Write[(Woozle, String)]: Unit
    Write[(Int, (Woozle, Woozle, String))]: Unit
  }

  test("Write should exist for option of some fancy types") {
    import doobie.util.Write.Auto.*

    Write[Option[Woozle]]: Unit
    Write[Option[(Woozle, String)]]: Unit
    Write[Option[(Int, (Woozle, Woozle, String))]]: Unit
  }
}
