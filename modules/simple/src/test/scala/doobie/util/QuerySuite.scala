// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.H2DatabaseSpec
import doobie.syntax.string.*
import doobie.util.query.Query
import doobie.util.query.Query0
import zio.test.assertTrue

object QuerySuite extends H2DatabaseSpec {

  private val q = Query[String, Int]("select 123 where ? = 'foo'", None)
  private val pairQuery = Query[String, (String, Int)]("select 'xxx', 123 where ? = 'foo'", None)

  override val spec = suite("Query")(
    suite("Query (non-empty)")(
      test("to") {
        q.to[List]("foo").transact.map { result =>
          assertTrue(result == List(123))
        }
      },
      test("toMap") {
        pairQuery.toMap[String, Int]("foo").transact.map { result =>
          assertTrue(result == Map("xxx" -> 123))
        }
      },
      test("unique") {
        q.unique("foo").transact.map { result =>
          assertTrue(result == 123)
        }
      },
      test("option") {
        q.option("foo").transact.map { result =>
          assertTrue(result == Some(123))
        }
      },
      test("map") {
        q.map("x" * _).to[List]("foo").transact.map { result =>
          assertTrue(result == List("x" * 123))
        }
      },
      test("contramap") {
        q.contramap[Int](n => "foo" * n).to[List](1).transact.map { result =>
          assertTrue(result == List(123))
        }
      },
    ),
    suite("Query (empty)")(
      test("to") {
        q.to[List]("bar").transact.map { result =>
          assertTrue(result == Nil)
        }
      },
      test("toMap") {
        pairQuery.toMap[String, Int]("bar").transact.map { result =>
          assertTrue(result == Map.empty[String, Int])
        }
      },
      test("unique") {
        q.unique("bar").transact.either.map { result =>
          assertTrue(result == Left(invariant.UnexpectedEnd()))
        }
      },
      test("option") {
        q.option("bar").transact.map { result =>
          assertTrue(result == None)
        }
      },
      test("map") {
        q.map("x" * _).to[List]("bar").transact.map { result =>
          assertTrue(result == Nil)
        }
      },
      test("contramap") {
        q.contramap[Int](n => "bar" * n).to[List](1).transact.map { result =>
          assertTrue(result == Nil)
        }
      },
    ),
    suite("Query0 from Query (non-empty)")(
      test("to") {
        q.toQuery0("foo").to[List].transact.map { result =>
          assertTrue(result == List(123))
        }
      },
      test("toMap") {
        pairQuery.toQuery0("foo").toMap[String, Int].transact.map { result =>
          assertTrue(result == Map("xxx" -> 123))
        }
      },
      test("unique") {
        q.toQuery0("foo").unique.transact.map { result =>
          assertTrue(result == 123)
        }
      },
      test("option") {
        q.toQuery0("foo").option.transact.map { result =>
          assertTrue(result == Some(123))
        }
      },
      test("map") {
        q.toQuery0("foo").map(_ * 2).to[List].transact.map { result =>
          assertTrue(result == List(246))
        }
      },
    ),
    suite("Query0 from Query (empty)")(
      test("to") {
        q.toQuery0("bar").to[List].transact.map { result =>
          assertTrue(result == Nil)
        }
      },
      test("toMap") {
        pairQuery.toQuery0("bar").toMap[String, Int].transact.map { result =>
          assertTrue(result == Map.empty[String, Int])
        }
      },
      test("unique") {
        q.toQuery0("bar").unique.transact.either.map { result =>
          assertTrue(result == Left(invariant.UnexpectedEnd()))
        }
      },
      test("option") {
        q.toQuery0("bar").option.transact.map { result =>
          assertTrue(result == None)
        }
      },
      test("map") {
        q.toQuery0("bar").map(_ * 2).to[List].transact.map { result =>
          assertTrue(result == Nil)
        }
      },
    ),
    suite("Query0 via constructor (non-empty)")({
      val q0n = Query0[Int]("select 123 where 'foo' = 'foo'", None)
      val pairQ0n = Query0[(String, Int)]("select 'xxx', 123 where 'foo' = 'foo'", None)

      test("to") {
        q0n.to[List].transact.map { result =>
          assertTrue(result == List(123))
        }
      } ::
      test("toMap") {
        pairQ0n.toMap[String, Int].transact.map { result =>
          assertTrue(result == Map("xxx" -> 123))
        }
      } ::
      test("unique") {
        q0n.unique.transact.map { result =>
          assertTrue(result == 123)
        }
      } ::
      test("option") {
        q0n.option.transact.map { result =>
          assertTrue(result == Some(123))
        }
      } ::
      test("map") {
        q0n.map(_ * 2).to[List].transact.map { result =>
          assertTrue(result == List(246))
        }
      } :: Nil
    }),
    suite("Query0 via constructor (empty)")({
      val q0e = Query0[Int]("select 123 where 'bar' = 'foo'", None)
      val pairQ0e = Query0[(String, Int)]("select 'xxx', 123 where 'bar' = 'foo'", None)

      test("to") {
        q0e.to[List].transact.map { result =>
          assertTrue(result == Nil)
        }
      } ::
      test("toMap") {
        pairQ0e.toMap[String, Int].transact.map { result =>
          assertTrue(result == Map.empty[String, Int])
        }
      } ::
      test("unique") {
        q0e.unique.transact.either.map { result =>
          assertTrue(result == Left(invariant.UnexpectedEnd()))
        }
      } ::
      test("option") {
        q0e.option.transact.map { result =>
          assertTrue(result == None)
        }
      } ::
      test("map") {
        q0e.map(_ * 2).to[List].transact.map { result =>
          assertTrue(result == Nil)
        }
      } :: Nil
    }),
    test("to Fragment and back") {
      val qf = fr"select 'foo', ${1: Int}, ${Option.empty[Int]}, ${Option(42)}".query[String] // wrong!
      val qf_ = qf.toFragment.query[(String, Int, Option[Int], Option[Int])]
      qf_.unique.transact.map { result =>
        assertTrue(result == ("foo", 1, None, Some(42)))
      }
    },
  )

}
