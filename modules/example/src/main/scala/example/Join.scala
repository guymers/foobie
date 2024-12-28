// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package example

import cats.syntax.apply.*
import doobie.syntax.string.*
import doobie.util.Read
import doobie.util.query.Query0

object Join {

  final case class Country(code: String, name: String)
  object Country {
    implicit val read: Read[Country] = Read.derived
  }
  final case class City(id: Int, name: String)
  object City {
    implicit val read: Read[City] = Read.derived
  }

  // Old style required examining joined columns individually
  def countriesAndCities1(filter: String): Query0[(Country, Option[City])] =
    sql"""
      SELECT k.code, k.name, c.id, c.name
      FROM country k
      LEFT OUTER JOIN city c
        ON k.code = c.countrycode AND c.name like $filter
    """.query[(Country, Option[Int], Option[String])]
      .map { case (k, a, b) => (k, (a, b).mapN(City(_, _))) }

  // New style (better)
  def countriesAndCities2(filter: String): Query0[(Country, Option[City])] =
    sql"""
      SELECT k.code, k.name, c.id, c.name
      FROM country k
      LEFT OUTER JOIN city c
        ON k.code = c.countrycode AND c.name like $filter
    """.query

}
