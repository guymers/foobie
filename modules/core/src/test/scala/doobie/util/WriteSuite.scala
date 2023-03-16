// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.H2DatabaseSpec
import doobie.test.illTyped
import doobie.util.meta.Meta
import zio.test.assertCompletes
import zio.test.assertTrue

import scala.annotation.nowarn

@nowarn("msg=.*Foo is never used.*")
object WriteSuite extends H2DatabaseSpec with WriteSuitePlatform {

  case class X(x: Int) extends AnyVal

  case class LenStr1(n: Int, s: String)

  case class LenStr2(n: Int, s: String)
  object LenStr2 {
    implicit val meta: Meta[LenStr2] = Meta[String].timap(s => LenStr2(s.length, s))(_.s)
  }

  case class Widget(n: Int, w: Widget.Inner)
  object Widget {
    case class Inner(n: Int, s: String)
  }

  case class Woozle(a: (String, Int), b: (Int, String), c: Boolean)

  override val spec = suite("Write")(
    test("exist for some fancy types") {
      import doobie.util.Write.Auto.*

      Write[Int]: Unit
      Write[(Int, Int)]: Unit
      Write[(Int, Int, String)]: Unit
      Write[(Int, (Int, String))]: Unit
      assertCompletes
    },
    test("is not auto derived without an import") {
      val _ = illTyped("Write[(Int, Int)]")
      val _ = illTyped("Write[(Int, Int, String)]")
      val _ = illTyped("Write[(Int, (Int, String))]")
      assertCompletes
    },
    test("can be manually derived") {
      assertTrue(Write.derived[LenStr1].length == 2)
    },
    test("deriving instances should work correctly from class scope") {
      import doobie.util.Write.Auto.*

      class Foo[A: Write, B: Write] {
        Write[(A, B)]: Unit
      }
      assertCompletes
    },
    test("exist for Unit") {
      import doobie.util.Write.Auto.*

      Write[Unit]: Unit
      assertTrue(Write[(Int, Unit)].length == 1)
    },
    test("exist for option of some fancy types") {
      import doobie.util.Write.Auto.*

      Write[Option[Int]]: Unit
      Write[Option[(Int, Int)]]: Unit
      Write[Option[(Int, Int, String)]]: Unit
      Write[Option[(Int, (Int, String))]]: Unit
      Write[Option[(Int, Option[(Int, String)])]]: Unit
      assertCompletes
    },
    test("auto derives nested types") {
      import doobie.util.Write.Auto.*

      assertTrue(Write[Widget].length == 3)
    },
    test("does not auto derive nested types without an import") {
      val _ = illTyped("Write.derived[Widget]")
      assertCompletes
    },
    test("exist for option of Unit") {
      import doobie.util.Write.Auto.*

      assertTrue(Write[Option[Unit]].length == 0) &&
      assertTrue(Write[Option[(Int, Unit)]].length == 1)
    },
    test("select multi-column instance by default") {
      import doobie.util.Write.Auto.*

      assertTrue(Write[LenStr1].length == 2)
    },
    test("select 1-column instance when available") {
      assertTrue(Write[LenStr2].length == 1)
    },
    test("exist for some fancy types") {
      import doobie.util.Write.Auto.*

      Write[Woozle]: Unit
      Write[(Woozle, String)]: Unit
      Write[(Int, (Woozle, Woozle, String))]: Unit
      assertCompletes
    },
    test("exist for option of some fancy types") {
      import doobie.util.Write.Auto.*

      Write[Option[Woozle]]: Unit
      Write[Option[(Woozle, String)]]: Unit
      Write[Option[(Int, (Woozle, Woozle, String))]]: Unit
      assertCompletes
    },
    suite("platform specific")(platformTests*),
  )
}
