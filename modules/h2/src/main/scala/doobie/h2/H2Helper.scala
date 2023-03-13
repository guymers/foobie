// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.h2

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.functor.*
import cats.syntax.show.*
import doobie.free.KleisliInterpreter
import doobie.util.transactor.Strategy
import doobie.util.transactor.Transactor

object H2Helper {

  // avoid the need for Class.forName
  private val driver = new org.h2.Driver

  def inMemory[M[_]](
    database: String,
    strategy: Strategy = Strategy.default,
  )(implicit M: Async[M]): Resource[M, Transactor[M]] = {

    val url = show"jdbc:h2:mem:$database;DB_CLOSE_DELAY=-1"

    def props = {
      val props = new java.util.Properties()
      props.put("user", "sa")
      props.put("password", "")
      props
    }

    val conn = Resource.fromAutoCloseable(M.blocking { driver.connect(url, props) })
    val transactor = Transactor[M, Unit]((), _ => conn, KleisliInterpreter[M].ConnectionInterpreter, strategy)

    val shutdown = conn.use { c =>
      M.blocking {
        c.createStatement().execute("SHUTDOWN")
      }.void
    }
    Resource.make(M.unit)(_ => shutdown).as(transactor)
  }

}
