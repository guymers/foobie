// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.syntax.foldable.*
import doobie.H2DatabaseSpec
import doobie.syntax.string.*
import doobie.util.fragment.Fragment
import zio.test.assertCompletes
import zio.test.assertTrue

object FragmentSuite extends H2DatabaseSpec {

  private val a = 1
  private val b = "two"
  private val c = true

  private val fra = fr"$a"
  private val frb = fr"$b"
  private val frc = fr"$c"

  override val spec = suite("Fragment")(
    test("substitute placeholders") {
      assertTrue(fr"foo $a $b bar".query[Unit].sql == "foo ? ? bar ")
    },
    test("concatenate") {
      assertTrue((fr"foo" ++ fr"bar $a baz").query[Unit].sql == "foo bar ? baz ")
    },
    test("interpolate") {
      assertTrue(fr"foo ${fr0"bar $a baz"}".query[Unit].sql == "foo bar ? baz ")
    },
    // https://github.com/tpolecat/doobie/issues/1186
    test("interpolate an expression `Option(1).getOrElse(2)`") {
      val _ = sql"${Option(1).getOrElse(2)} ${false} ${"xx"}"
      val _ = fr"${Option(1).getOrElse(2)}"
      val _ = fr0"${Option(1).getOrElse(2)}"
      assertCompletes
    },
    test("maintain parameter indexing (in-order)") {
      val s = fr"select" ++ List(fra, frb, frc).intercalate(fr",")
      s.query[(Int, String, Boolean)].unique.transact.map { result =>
        assertTrue(result == (a, b, c))
      }
    },
    test("maintain parameter indexing (out-of-order)") {
      val s = fr"select" ++ List(frb, frc, fra).intercalate(fr",")
      s.query[(String, Boolean, Int)].unique.transact.map { result =>
        assertTrue(result == (b, c, a))
      }
    },
    test("maintain associativity (left)") {
      val s = fr"select" ++ List(fra, fr",", frb, fr",", frc).foldLeft(Fragment.empty)(_ ++ _)
      s.query[(Int, String, Boolean)].unique.transact.map { result =>
        assertTrue(result == (a, b, c))
      }
    },
    test("maintain associativity (right)") {
      val s = fr"select" ++ List(fra, fr",", frb, fr",", frc).foldRight(Fragment.empty)(_ ++ _)
      s.query[(Int, String, Boolean)].unique.transact.map { result =>
        assertTrue(result == (a, b, c))
      }
    },
    test("add a trailing space when constructed with .const") {
      assertTrue(Fragment.const("foo").query[Int].sql == "foo ")
    },
    test("not add a trailing space when constructed with .const0") {
      assertTrue(Fragment.const0("foo").query[Int].sql == "foo")
    },
    suite("margin stripping")(
      test("default") {
        val s =
          fr"""select foo
            |  from bar
            |  where a = $a and b = $b and c = $c
            |""".stripMargin
        assertTrue(s.query[Int].sql == "select foo\n  from bar\n  where a = ? and b = ? and c = ?\n ")
      },
      test("custom margin") {
        val s =
          fr"""select foo
            !  from bar
            !""".stripMargin('!')
        assertTrue(s.query[Int].sql == "select foo\n  from bar\n ")
      },
      test("ignore margin characters outside of margin position") {
        val s =
          fr"""select foo || baz
            |  from bar
            |""".stripMargin
        assertTrue(s.query[Int].sql == "select foo || baz\n  from bar\n ")
      },
    ),
    suite("stacksafe")({

      // A fragment composed of this many sub-fragments would not be stacksafe without special
      // handling, which we test below.
      val STACK_UNSAFE_SIZE = 20000

      test("left-associative") {
        val frag =
          fr0"SELECT 1 WHERE 1 IN (" ++
            List.fill(STACK_UNSAFE_SIZE)(1).foldLeft(Fragment.empty)((f, n) => f ++ fr"$n,") ++
            fr0"1)"
        frag.query[Int].unique.transact.map { result =>
          assertTrue(result == 1)
        }
      } ::
      test("right-associative") {
        val frag =
          fr0"SELECT 1 WHERE 1 IN (" ++
            List.fill(STACK_UNSAFE_SIZE)(1).foldRight(Fragment.empty)((n, f) => f ++ fr"$n,") ++
            fr0"1)"
        frag.query[Int].unique.transact.map { result =>
          assertTrue(result == 1)
        }
      } :: Nil
    }),
  )
}
