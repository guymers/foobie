// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package example

import doobie.postgres.instances.array.*
import doobie.postgres.instances.geometric.*
import doobie.syntax.string.*
import doobie.util.Read
import doobie.util.Write
import doobie.util.query.Query0
import doobie.util.update.Update
import doobie.util.update.Update0
import org.postgresql.geometric.*

// Some queries to test using the AnalysisTestSpec in src/test
object AnalysisTest {

  final case class Country(name: String, indepYear: Int)
  object Country {
    implicit val read: Read[Country] = Read.derived
    implicit val write: Write[Country] = Write.derived
  }

  def speakerQuery(lang: String, pct: Double): Query0[Country] =
    sql"""
      SELECT C.NAME, C.INDEPYEAR, C.CODE FROM COUNTRYLANGUAGE CL
      JOIN COUNTRY C ON CL.COUNTRYCODE = C.CODE
      WHERE LANGUAGE = $lang AND PERCENTAGE > $pct
    """.query[Country]

  val speakerQuery2 =
    sql"""
      SELECT C.NAME, C.INDEPYEAR, C.CODE FROM COUNTRYLANGUAGE CL
      JOIN COUNTRY C ON CL.COUNTRYCODE = C.CODE
    """.query[(String, Option[Short], String)]

  val arrayTest =
    sql"""
      SELECT ARRAY[1, 2, NULL] test
    """.query[Option[List[String]]]

  val arrayTest2 =
    sql"""
      SELECT ARRAY[1, 2, NULL] test
    """.query[String]

  val pointTest =
    sql"""
      SELECT '(1, 2)'::point test
    """.query[PGpoint]

  val pointTest2 = {
    sql"""
      SELECT '(1, 2)'::point test
    """.query[PGcircle]
  }

  def update: Update[(String, String)] = {
    Update[(String, String)](
      "UPDATE COUNTRY SET NAME = ? WHERE CODE = ?",
    )
  }

  def update0_1(name: String, code: String): Update0 =
    sql"""
      UPDATE COUNTRY SET NAME = $name WHERE CODE = $code
    """.update

  val update0_2: Update0 =
    sql"""
      UPDATE COUNTRY SET NAME = 'foo' WHERE CODE = 'bkah'
    """.update

}
