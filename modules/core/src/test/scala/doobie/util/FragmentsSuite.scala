// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.data.NonEmptyList
import doobie.H2DatabaseSpec
import doobie.syntax.string.*
import doobie.util.meta.Meta
import zio.test.assertTrue

object FragmentsSuite extends H2DatabaseSpec {
  import doobie.util.fragments.*

  private val nel = NonEmptyList.of(1, 2, 3)
  private val fs = List(1, 2, 3).map(n => fr"$n")
  private val ofs = List(1, 2, 3).map(n => Some(fr"$n").filter(_ => n % 2 != 0))

  implicit val arrayInt: Put[Array[Int]] = Meta.Advanced.array[Integer]("INT", "ARRAY").put
    .contramap(arr => arr.map(i => i: Integer))

  override val spec = suite("Fragments")(
    test("any") {
      assertTrue(any(nel).query[Unit].sql == "ANY(?) ")
    },
    test("commas for one column") {
      assertTrue(commas(NonEmptyList.of(1, 2)).query[Unit].sql == "?, ? ")
    },
    test("commas for two columns") {
      assertTrue(commas(NonEmptyList.of((1, true), (2, false))).query[Unit].sql == "(?,?), (?,?) ")
    },
    suite("and")(
      test("many") {
        assertTrue(and(fs*).query[Unit].sql == "(? ) AND (? ) AND (? ) ")
      },
      test("single") {
        assertTrue(and(fs(0)).query[Unit].sql == "(? ) ")
      },
      test("empty") {
        assertTrue(and().query[Unit].sql == "")
      },
    ),
    suite("andOpt")(
      test("many") {
        assertTrue(andOpt(ofs*).query[Unit].sql == "(? ) AND (? ) ")
      },
      test("one") {
        assertTrue(andOpt(ofs(0)).query[Unit].sql == "(? ) ")
      },
      test("none") {
        assertTrue(andOpt(None, None).query[Unit].sql == "")
      },
    ),
    test("or (many)") {
      assertTrue(or(fs*).query[Unit].sql == "(? ) OR (? ) OR (? ) ")
    },
    test("or (single)") {
      assertTrue(or(fs(0)).query[Unit].sql == "(? ) ")
    },
    test("or (empty)") {
      assertTrue(or().query[Unit].sql == "")
    },
    test("orOpt (many)") {
      assertTrue(orOpt(ofs*).query[Unit].sql == "(? ) OR (? ) ")
    },
    test("orOpt (one)") {
      assertTrue(orOpt(ofs(0)).query[Unit].sql == "(? ) ")
    },
    test("orOpt (none)") {
      assertTrue(orOpt(None, None).query[Unit].sql == "")
    },
    test("whereAnd (many)") {
      assertTrue(whereAnd(fs*).query[Unit].sql == "WHERE (? ) AND (? ) AND (? ) ")
    },
    test("whereAnd (single)") {
      assertTrue(whereAnd(fs(0)).query[Unit].sql == "WHERE (? ) ")
    },
    test("whereAnd (empty)") {
      assertTrue(whereAnd().query[Unit].sql == "")
    },
    test("whereAndOpt (many)") {
      assertTrue(whereAndOpt(ofs*).query[Unit].sql == "WHERE (? ) AND (? ) ")
    },
    test("whereAndOpt (one)") {
      assertTrue(whereAndOpt(ofs(0)).query[Unit].sql == "WHERE (? ) ")
    },
    test("whereAndOpt (none)") {
      assertTrue(whereAndOpt(None, None).query[Unit].sql == "")
    },
    test("whereOr (many)") {
      assertTrue(whereOr(fs*).query[Unit].sql == "WHERE (? ) OR (? ) OR (? ) ")
    },
    test("whereOr (single)") {
      assertTrue(whereOr(fs(0)).query[Unit].sql == "WHERE (? ) ")
    },
    test("whereOr (empty)") {
      assertTrue(whereOr().query[Unit].sql == "")
    },
    test("whereOrOpt (many)") {
      assertTrue(whereOrOpt(ofs*).query[Unit].sql == "WHERE (? ) OR (? ) ")
    },
    test("whereOrOpt (one)") {
      assertTrue(whereOrOpt(ofs(0)).query[Unit].sql == "WHERE (? ) ")
    },
    test("whereOrOpt (none)") {
      assertTrue(whereAndOpt(None, None).query[Unit].sql == "")
    },
    test("values (1)") {
      val c = Contact(Person("Bob", 42), Some("addr"))
      val f = fr"select ${values(c)}"
      f.query[Contact].unique.transact.map { result =>
        assertTrue(result == c)
      }
    },
    test("values (2)") {
      val c = Contact(Person("Bob", 42), None)
      val f = fr"select ${values(c)}"
      f.query[Contact].unique.transact.map { result =>
        assertTrue(result == c)
      }
    },
  )

  case class Person(name: String, age: Int)
  object Person {
    implicit val read: Read[Person] = Read.derived
    implicit val write: Write[Person] = Write.derived
  }

  case class Contact(person: Person, address: Option[String])
  object Contact {
    implicit val read: Read[Contact] = Read.derived
    implicit val write: Write[Contact] = Write.derived
  }

}
