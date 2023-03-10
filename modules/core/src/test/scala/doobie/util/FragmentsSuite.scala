// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import doobie.syntax.connectionio.*
import doobie.syntax.string.*
import doobie.util.meta.Meta
import doobie.util.transactor.Transactor

import scala.annotation.nowarn

@nowarn("cat=deprecation")
class FragmentsSuite extends munit.FunSuite {
  import cats.effect.unsafe.implicits.global
  import doobie.util.Write.Auto.*
  import doobie.util.fragments.*

  val xa = Transactor.fromDriverManager[IO](
    "org.h2.Driver",
    "jdbc:h2:mem:queryspec;DB_CLOSE_DELAY=-1",
    "sa",
    "",
  )

  val nel = NonEmptyList.of(1, 2, 3)
  val fs = List(1, 2, 3).map(n => fr"$n")
  val ofs = List(1, 2, 3).map(n => Some(fr"$n").filter(_ => n % 2 =!= 0))

  implicit val arrayInt: Put[Array[Int]] = Meta.Advanced.array[Integer]("INT", "ARRAY").put
    .contramap(arr => arr.map(i => i: Integer))

  test("any") {
    assertEquals(any(nel).query[Unit].sql, "ANY(?) ")
  }

  test("any iterable") {
    assertEquals(anyIterable(nel.toList).query[Unit].sql, "ANY(?) ")
  }

  test("commas for one column") {
    assertEquals(commas(NonEmptyList.of(1, 2)).query[Unit].sql, "?, ? ")
  }

  test("commas for two columns") {
    assertEquals(commas(NonEmptyList.of((1, true), (2, false))).query[Unit].sql, "(?,?), (?,?) ")
  }

  test("values for two columns") {
    assertEquals(values(NonEmptyList.of((1, true), (2, false))).query[Unit].sql, "VALUES (?,?), (?,?) ")
  }

  test("values for one column") {
    assertEquals(values(nel).query[Unit].sql, "VALUES (?), (?), (?) ")
  }

  test("values for two columns") {
    assertEquals(values(NonEmptyList.of((1, true), (2, false))).query[Unit].sql, "VALUES (?,?), (?,?) ")
  }

  test("in for one column") {
    assertEquals(in(fr"foo", nel).query[Unit].sql, "foo IN (?, ?, ?) ")
  }

  test("in for two columns") {
    assertEquals(in(fr"foo", NonEmptyList.of((1, true), (2, false))).query[Unit].sql, "foo IN ((?,?), (?,?)) ")
  }

  test("notIn") {
    assertEquals(notIn(fr"foo", nel).query[Unit].sql, "foo NOT IN (?, ?, ?) ")
  }

  test("and (many)") {
    assertEquals(and(fs*).query[Unit].sql, "(? ) AND (? ) AND (? ) ")
  }

  test("and (single)") {
    assertEquals(and(fs(0)).query[Unit].sql, "(? ) ")
  }

  test("and (empty)") {
    assertEquals(and().query[Unit].sql, "")
  }

  test("andOpt (many)") {
    assertEquals(andOpt(ofs*).query[Unit].sql, "(? ) AND (? ) ")
  }

  test("andOpt (one)") {
    assertEquals(andOpt(ofs(0)).query[Unit].sql, "(? ) ")
  }

  test("andOpt (none)") {
    assertEquals(andOpt(None, None).query[Unit].sql, "")
  }

  test("or (many)") {
    assertEquals(or(fs*).query[Unit].sql, "(? ) OR (? ) OR (? ) ")
  }

  test("or (single)") {
    assertEquals(or(fs(0)).query[Unit].sql, "(? ) ")
  }

  test("or (empty)") {
    assertEquals(or().query[Unit].sql, "")
  }

  test("orOpt (many)") {
    assertEquals(orOpt(ofs*).query[Unit].sql, "(? ) OR (? ) ")
  }

  test("orOpt (one)") {
    assertEquals(orOpt(ofs(0)).query[Unit].sql, "(? ) ")
  }

  test("orOpt (none)") {
    assertEquals(orOpt(None, None).query[Unit].sql, "")
  }

  test("whereAnd (many)") {
    assertEquals(whereAnd(fs*).query[Unit].sql, "WHERE (? ) AND (? ) AND (? ) ")
  }

  test("whereAnd (single)") {
    assertEquals(whereAnd(fs(0)).query[Unit].sql, "WHERE (? ) ")
  }

  test("whereAnd (empty)") {
    assertEquals(whereAnd().query[Unit].sql, "")
  }

  test("whereAndOpt (many)") {
    assertEquals(whereAndOpt(ofs*).query[Unit].sql, "WHERE (? ) AND (? ) ")
  }

  test("whereAndOpt (one)") {
    assertEquals(whereAndOpt(ofs(0)).query[Unit].sql, "WHERE (? ) ")
  }

  test("whereAndOpt (none)") {
    assertEquals(whereAndOpt(None, None).query[Unit].sql, "")
  }

  test("whereOr (many)") {
    assertEquals(whereOr(fs*).query[Unit].sql, "WHERE (? ) OR (? ) OR (? ) ")
  }

  test("whereOr (single)") {
    assertEquals(whereOr(fs(0)).query[Unit].sql, "WHERE (? ) ")
  }

  test("whereOr (empty)") {
    assertEquals(whereOr().query[Unit].sql, "")
  }

  test("whereOrOpt (many)") {
    assertEquals(whereOrOpt(ofs*).query[Unit].sql, "WHERE (? ) OR (? ) ")
  }

  test("whereOrOpt (one)") {
    assertEquals(whereOrOpt(ofs(0)).query[Unit].sql, "WHERE (? ) ")
  }

  test("whereOrOpt (none)") {
    assertEquals(whereOrOpt(None, None).query[Unit].sql, "")
  }

  case class Person(name: String, age: Int)
  object Person {
    implicit val read: Read[Person] = Read.derived
  }

  case class Contact(person: Person, address: Option[String])
  object Contact {
    implicit val read: Read[Contact] = Read.derived
  }

  test("values (1)") {
    val c = Contact(Person("Bob", 42), Some("addr"))
    val f = fr"select ${values(c)}"
    assertEquals(f.query[Contact].unique.transact(xa).unsafeRunSync(), c)
  }

  test("values (2)") {
    val c = Contact(Person("Bob", 42), None)
    val f = fr"select ${values(c)}"
    assertEquals(f.query[Contact].unique.transact(xa).unsafeRunSync(), c)
  }

}
