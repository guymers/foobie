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
  object LenStr1 {
    implicit val read: Read[LenStr1] = Read.derived
  }

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
  object Woozle {
    implicit val read: Read[Woozle] = Read.derived
  }

  override val spec = suite("Read")(
    test("tuples derive") {
      val _ = Read[Int]
      val _ = Read[(Int, Int)]
      val _ = Read[(Int, Int, String)]
      val _ = Read[(Int, (Int, String))]
      assertCompletes
    },
    test("does not auto derive nested types without an import") {
      val _ = illTyped("Read.derived[Widget]")
      assertCompletes
    },
    test("can be manually derived") {
      assertTrue(Read.derived[LenStr1].length == 2)
    },
    test("exist for Unit") {
      assertTrue(Read[Unit].length == 0) &&
      assertTrue(Read[(Int, Unit)].length == 1)
    },
    test("option tuples derive") {
      val _ = Read[Option[Int]]
      val _ = Read[Option[(Int, Int)]]
      val _ = Read[Option[(Int, Int, String)]]
      val _ = Read[Option[(Int, (Int, String))]]
      val _ = Read[Option[(Int, Option[(Int, String)])]]
      assertCompletes
    },
    test("exist for option of Unit") {
      assertTrue(Read[Option[Unit]].length == 0) &&
      assertTrue(Read[Option[(Int, Unit)]].length == 1)
    },
    test("select multi-column instance by default") {
      assertTrue(Read[LenStr1].length == 2)
    },
    test("select 1-column instance when available") {
      assertTrue(Read[LenStr2].length == 1)
    },
    test("exist for some fancy types") {
      val _ = Read[Woozle]
      val _ = Read[(Woozle, String)]
      val _ = Read[(Int, (Woozle, Woozle, String))]
      assertCompletes
    },
    test("exist for option of some fancy types") {
      val _ = Read[Option[Woozle]]
      val _ = Read[Option[(Woozle, String)]]
      val _ = Read[Option[(Int, (Woozle, Woozle, String))]]
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
