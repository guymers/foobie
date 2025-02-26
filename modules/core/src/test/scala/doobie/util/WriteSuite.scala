// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.syntax.apply.*
import doobie.H2DatabaseSpec
import doobie.syntax.string.*
import doobie.test.illTyped
import doobie.util.meta.Meta
import doobie.util.update.Update
import doobie.util.update.Update0
import zio.test.assertCompletes
import zio.test.assertTrue

import scala.annotation.nowarn

@nowarn("msg=.*Foo is never used.*")
object WriteSuite extends H2DatabaseSpec with WriteSuitePlatform {

  case class X(x: Int) extends AnyVal

  case class LenStr1(n: Int, s: String)
  object LenStr1 {
    implicit val write: Write[LenStr1] = Write.derived
  }

  case class LenStr2(n: Int, s: String)
  object LenStr2 {
    implicit val meta: Meta[LenStr2] = Meta[String].timap(s => LenStr2(s.length, s))(_.s)
  }

  case class Widget(n: Int, w: Widget.Inner)
  object Widget {
    case class Inner(n: Int, s: String)
  }

  case class Woozle(a: (String, Int), b: (Int, String), c: Boolean)
  object Woozle {
    implicit val write: Write[Woozle] = Write.derived
  }

  override val spec = suite("Write")(
    test("tuples derive") {
      val _ = Write[Int]
      val _ = Write[(Int, Int)]
      val _ = Write[(Int, Int, String)]
      val _ = Write[(Int, (Int, String))]
      assertCompletes
    },
    test("can be manually derived") {
      assertTrue(Write.derived[LenStr1].length == 2)
    },
    test("deriving instances should work correctly from class scope") {
      class Foo[A: Write, B: Write] {
        locally {
          val _ = Write[(A, B)]
        }
      }
      assertCompletes
    },
    test("exist for Unit") {
      val _ = Write[Unit]
      assertTrue(Write[(Int, Unit)].length == 1)
    },
    test("option tuples derive") {
      val _ = Write[Option[Int]]
      val _ = Write[Option[(Int, Int)]]
      val _ = Write[Option[(Int, Int, String)]]
      val _ = Write[Option[(Int, (Int, String))]]
      val _ = Write[Option[(Int, Option[(Int, String)])]]
      assertCompletes
    },
    test("does not auto derive") {
      val _ = illTyped("Write.derived[Widget]")
      assertCompletes
    },
    test("exist for option of Unit") {
      assertTrue(Write[Option[Unit]].length == 0) &&
      assertTrue(Write[Option[(Int, Unit)]].length == 1)
    },
    test("select multi-column instance by default") {
      assertTrue(Write[LenStr1].length == 2)
    },
    test("select 1-column instance when available") {
      assertTrue(Write[LenStr2].length == 1)
    },
    test("exist for some fancy types") {
      val _ = Write[Woozle]
      val _ = Write[(Woozle, String)]
      val _ = Write[(Int, (Woozle, Woozle, String))]
      assertCompletes
    },
    test("exist for option of some fancy types") {
      val _ = Write[Option[Woozle]]
      val _ = Write[Option[(Woozle, String)]]
      val _ = Write[Option[(Int, (Woozle, Woozle, String))]]
      assertCompletes
    },
    suite("write correctly")(
      test("fragment") {

        def insert[A](a: A)(implicit W: Write[A]) = {
          fr"INSERT INTO test_write (v, s) VALUES ($a)".update.withUniqueGeneratedKeys[Test]("v", "s")
        }

        val conn = for {
          _ <-
            Update0("CREATE LOCAL TEMPORARY TABLE IF NOT EXISTS test_write(v INT, s VARCHAR) NOT PERSISTENT", None).run

          t1 <- insert(Test(None, None))(Test.write)
          t2 <- insert(Test(None, Some("str")))(Test.write)
          t3 <- insert(Test(Some(3), Some("str")))(Test.write)

          tup1 <- insert[(Option[Int], Option[String])]((None, None))(Test.writeTuple)
          tup2 <- insert[(Option[Int], Option[String])]((None, Some("str")))(Test.writeTuple)
          tup3 <- insert[(Option[Int], Option[String])]((Some(3), Some("str")))(Test.writeTuple)
        } yield {
          assertTrue(t1 == Test(None, None)) &&
          assertTrue(t2 == Test(None, Some("str"))) &&
          assertTrue(t3 == Test(Some(3), Some("str"))) &&
          assertTrue(tup1 == Test(None, None)) &&
          assertTrue(tup2 == Test(None, Some("str"))) &&
          assertTrue(tup3 == Test(Some(3), Some("str")))
        }
        conn.transact
      },
      test("parameterized") {

        def insert[A](a: A)(implicit W: Write[A]) = {
          Update[A]("INSERT INTO test_write_p (v, s) VALUES (?, ?)").withUniqueGeneratedKeys[Test]("v", "s")(a)
        }

        val conn = for {
          _ <- Update0(
            "CREATE LOCAL TEMPORARY TABLE IF NOT EXISTS test_write_p(v INT, s VARCHAR) NOT PERSISTENT",
            None,
          ).run

          t1 <- insert(Test(None, None))(Test.write)
          t2 <- insert(Test(None, Some("str")))(Test.write)
          t3 <- insert(Test(Some(3), Some("str")))(Test.write)

          tup1 <- insert[(Option[Int], Option[String])]((None, None))(Test.writeTuple)
          tup2 <- insert[(Option[Int], Option[String])]((None, Some("str")))(Test.writeTuple)
          tup3 <- insert[(Option[Int], Option[String])]((Some(3), Some("str")))(Test.writeTuple)
        } yield {
          assertTrue(t1 == Test(None, None)) &&
          assertTrue(t2 == Test(None, Some("str"))) &&
          assertTrue(t3 == Test(Some(3), Some("str"))) &&
          assertTrue(tup1 == Test(None, None)) &&
          assertTrue(tup2 == Test(None, Some("str"))) &&
          assertTrue(tup3 == Test(Some(3), Some("str")))
        }
        conn.transact
      },
      test("nested") {

        def insert(a: TestNested) = {
          fr"INSERT INTO test_write_n (v, s) VALUES ($a)".update.withUniqueGeneratedKeys[TestNested]("v", "s")
        }

        val conn = for {
          _ <- Update0(
            "CREATE LOCAL TEMPORARY TABLE IF NOT EXISTS test_write_n(v INT, s VARCHAR) NOT PERSISTENT",
            None,
          ).run

          t0 <- insert(TestNested(None))
          t1 <- insert(TestNested(Some(Test(None, None))))
          t2 <- insert(TestNested(Some(Test(None, Some("str")))))
          t3 <- insert(TestNested(Some(Test(Some(3), Some("str")))))
        } yield {
          assertTrue(t0 == TestNested(None)) &&
          assertTrue(t1 == TestNested(None)) &&
          assertTrue(t2 == TestNested(Some(Test(None, Some("str"))))) &&
          assertTrue(t3 == TestNested(Some(Test(Some(3), Some("str")))))
        }
        conn.transact
      },
    ),
    suite("platform specific")(platformTests*),
  )

  case class Test(v: Option[Int], s: Option[String])
  object Test {
    implicit val read: Read[Test] = Read.derived
    val write: Write[Test] = Write.derived
    val writeTuple = (Write[Option[Int]], Write[Option[String]]).tupled
  }

  case class TestNested(n: Option[Test])
  object TestNested {
    implicit val read: Read[TestNested] = Read.derived
    implicit val writeTest: Write[Test] = Test.write
    implicit val write: Write[TestNested] = Write.derived
  }

}
