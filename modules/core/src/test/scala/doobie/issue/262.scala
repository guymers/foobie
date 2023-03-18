// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.issue

import doobie.H2DatabaseSpec
import doobie.HC
import doobie.HPS
import doobie.free.KleisliInterpreter
import doobie.util.transactor.Transactor
import zio.Task
import zio.ZIO
import zio.test.assertTrue

object `262` extends H2DatabaseSpec {

  // an interpreter that returns null when we ask for statement metadata
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  object Interp extends KleisliInterpreter[Task] {
    override lazy val PreparedStatementInterpreter =
      new PreparedStatementInterpreter {
        override def getMetaData = primitive(_ => null)
      }
  }

  override val spec = suite("262")(
    test("getColumnJdbcMeta should handle null metadata") {
      for {
        transactor <- ZIO.service[Transactor[Task]]
        xa = Transactor.interpret.set(transactor, Interp.ConnectionInterpreter)
        prog = HC.prepareStatement("select 1")(HPS.getColumnJdbcMeta)
        result <- xa.trans(instance)(prog)
      } yield {
        assertTrue(result == Nil)
      }
    },
  )

}
