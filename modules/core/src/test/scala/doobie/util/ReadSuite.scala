// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.syntax.apply.*
import doobie.H2DatabaseSpec
import doobie.syntax.string.*
import doobie.test.illTyped
import doobie.util.meta.Meta
import zio.test.assertCompletes
import zio.test.assertTrue

object ReadSuite extends H2DatabaseSpec with ReadSuitePlatform {

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

  case class Woozle(a: (String, Int), b: (Int, String), c: Boolean)

  override val spec = suite("Read")(
    test("exist for some fancy types") {
      import doobie.util.Read.Auto.*

      Read[Int]: Unit
      Read[(Int, Int)]: Unit
      Read[(Int, Int, String)]: Unit
      Read[(Int, (Int, String))]: Unit
      assertCompletes
    },
    test("Read is not auto derived without an import") {
      val _ = illTyped("Read[(Int, Int)]")
      val _ = illTyped("Read[(Int, Int, String)]")
      val _ = illTyped("Read[(Int, (Int, String))]")
      assertCompletes
    },
    test("Read auto derives nested types") {
      import doobie.util.Read.Auto.*

      assertTrue(Read[Widget].length == 3)
    },
    test("Read does not auto derive nested types without an import") {
      val _ = illTyped("Read.derived[Widget]")
      assertCompletes
    },
    test("Read can be manually derived") {
      assertTrue(Read.derived[LenStr1].length == 2)
    },
    test("exist for Unit") {
      import doobie.util.Read.Auto.*

      assertTrue(Read[Unit].length == 0) &&
      assertTrue(Read[(Int, Unit)].length == 1)
    },
    test("exist for option of some fancy types") {
      import doobie.util.Read.Auto.*

      Read[Option[Int]]: Unit
      Read[Option[(Int, Int)]]: Unit
      Read[Option[(Int, Int, String)]]: Unit
      Read[Option[(Int, (Int, String))]]: Unit
      Read[Option[(Int, Option[(Int, String)])]]: Unit
      assertCompletes
    },
    test("exist for option of Unit") {
      import doobie.util.Read.Auto.*

      assertTrue(Read[Option[Unit]].length == 0) &&
      assertTrue(Read[Option[(Int, Unit)]].length == 1)
    },
    test("select multi-column instance by default") {
      import doobie.util.Read.Auto.*

      assertTrue(Read[LenStr1].length == 2)
    },
    test("select 1-column instance when available") {
      assertTrue(Read[LenStr2].length == 1)
    },
    test("exist for some fancy types") {
      import doobie.util.Read.Auto.*

      Read[Woozle]: Unit
      Read[(Woozle, String)]: Unit
      Read[(Int, (Woozle, Woozle, String))]: Unit
      assertCompletes
    },
    test("exist for option of some fancy types") {
      import doobie.util.Read.Auto.*

      Read[Option[Woozle]]: Unit
      Read[Option[(Woozle, String)]]: Unit
      Read[Option[(Int, (Woozle, Woozle, String))]]: Unit
      assertCompletes
    },
    test(".product should product the correct ordering of gets") {
      val readInt = Read[Int]
      val readString = Read[String]

      val p = readInt.product(readString)

      assertTrue(p.gets == readInt.gets ++ readString.gets)
    },
    test("select correct columns when combined with `ap`") {
      val r = Read[Int]
      val c = (r, r, r, r, r).tupled
      fr"SELECT 1, 2, 3, 4, 5".query(c).to[List].transact.map { result =>
        assertTrue(result == List((1, 2, 3, 4, 5)))
      }
    },
    test("select correct columns when combined with `product`") {
      val r = Read[Int].product(Read[Int].product(Read[Int]))

      fr"SELECT 1, 2, 3".query(r).to[List].transact.map { result =>
        assertTrue(result == List((1, (2, 3))))
      }
    },
    test("select correct columns when combined with `productL`") {
      val r = Read[Int].productL(Read[Int])

      fr"SELECT 1, 2".query(r).to[List].transact.map { result =>
        assertTrue(result == List(1))
      }
    },
    test("select correct columns when combined with `productR`") {
      val r = Read[Int].productR(Read[Int])

      fr"SELECT 1, 2".query(r).to[List].transact.map { result =>
        assertTrue(result == List(2))
      }
    },
    test("handles optional nested types") {
      for {
        a <- fr"SELECT 'a', 'a1', 1, false, 'a2', 2".query[Nested].unique.transact
        b <- fr"SELECT 'a', 'a1', 1, false, NULL, NULL".query[Nested].unique.transact
        c <- fr"SELECT 'a', NULL, 1, false, NULL, NULL".query[Nested].unique.transact
        d <- fr"SELECT 'a', 'a1', NULL, false, NULL, 2".query[Nested].unique.transact
      } yield {
        assertTrue(a == Nested("a", Some(NoneOptional("a1", 1)), false, Some(AllOptional(Some("a2"), Some(2))))) &&
        assertTrue(b == Nested("a", Some(NoneOptional("a1", 1)), false, None)) &&
        assertTrue(c == Nested("a", None, false, None)) &&
        assertTrue(d == Nested("a", None, false, Some(AllOptional(None, Some(2)))))
      }
    },
    suite("platform specific")(platformTests*),
  )

}
