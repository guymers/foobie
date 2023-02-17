// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package example

import cats.effect.IO
import doobie.specs2.analysisspec.*
import doobie.util.transactor.Transactor
import org.specs2.mutable.Specification

class AnalysisTestSpecs2 extends Specification with IOChecker {

  val transactor = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql:world",
    "postgres",
    "password",
  )
  // Commented tests fail!
  // check(AnalysisTest.speakerQuery(null, 0)): Unit
  check(AnalysisTest.speakerQuery2): Unit
  check(AnalysisTest.arrayTest): Unit
  // check(AnalysisTest.arrayTest2): Unit
  check(AnalysisTest.pointTest): Unit
  // check(AnalysisTest.pointTest2): Unit
  checkOutput(AnalysisTest.update): Unit
  checkOutput(AnalysisTest.update0_1("foo", "bkah")): Unit
  check(AnalysisTest.update0_2): Unit
}
