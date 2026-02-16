// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package example

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import cats.syntax.list.*
import cats.syntax.traverse.*
import doobie.syntax.string.*
import doobie.util.Read
import doobie.util.fragment.Fragment
import doobie.util.transactor.Transactor

import java.sql.DriverManager

object FragmentExample extends IOApp.Simple {

  // Import some convenience constructors.
  import doobie.util.fragments.commas
  import doobie.util.fragments.whereAndOpt

  // Country Info
  final case class Info(name: String, code: String, population: Int)
  object Info {
    implicit val read: Read[Info] = Read.derived
  }

  // Construct a Query0 with some optional filter conditions and a configurable LIMIT.
  def select(name: Option[String], pop: Option[Int], codes: List[String], limit: Long) = {

    // Three Option[Fragment] filter conditions.
    val f1 = name.map(s => fr"name LIKE $s")
    val f2 = pop.map(n => fr"population > $n")
    val f3 = codes.toNel.map(cs => fr"code IN (${commas(cs)}")

    // Our final query
    val q: Fragment = fr"""
      SELECT name, code, population FROM country
      ${whereAndOpt(f1, f2, f3)}
      LIMIT $limit
    """

    // Construct a Query0
    q.query[Info]

  }

  // Our world database
  val xa = {
    val conn = Resource.fromAutoCloseable(IO.blocking {
      DriverManager.getConnection("jdbc:postgresql:world", "postgres", "password")
    })
    Transactor.catsEffect((), conn)
  }

  // Some quick examples.
  val prog = List(
    select(None, None, Nil, 10),
    select(Some("U%"), None, Nil, 10),
    select(None, Some(100000000), Nil, 10),
    select(Some("U%"), None, List("USA", "GBR", "FRA"), 10),
    select(Some("U%"), Some(100000000), List("USA", "GBR", "FRA"), 10),
  ).traverse { q =>
    q.to[List]
  }

  def run: IO[Unit] =
    xa.run(prog).void

}
