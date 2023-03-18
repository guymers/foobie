// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.h2

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.show.*
import doobie.free.KleisliInterpreter
import doobie.util.transactor.Strategy
import doobie.util.transactor.Transactor
import org.h2.jdbcx.JdbcConnectionPool

import java.sql.Connection

object H2Helper {

  // avoid the need for Class.forName
  private val driver = new org.h2.Driver

  def inMemory[M[_]](
    database: String,
    strategy: Strategy = Strategy.default,
  )(implicit M: Sync[M]): Resource[M, Transactor[M]] = {

    val url = jdbcUrl(database)

    def props = {
      val props = new java.util.Properties()
      val _ = props.put("user", "sa")
      val _ = props.put("password", "")
      props
    }

    val conn = Resource.fromAutoCloseable(M.blocking { driver.connect(url, props) })
    shutdownDatabase(conn).map(_ => createTransactor(conn, strategy))
  }

  def inMemoryPooled[M[_]](
    database: String,
    maxConnections: Int = 10,
    strategy: Strategy = Strategy.default,
  )(implicit M: Sync[M]): Resource[M, Transactor[M]] = {

    def createPool = {
      val pool = JdbcConnectionPool.create(jdbcUrl(database), "sa", "")
      pool.setMaxConnections(maxConnections)
      pool
    }

    for {
      pool <- Resource.make(M.delay(createPool)) { pool => M.delay(pool.dispose()) }
      conn = Resource.fromAutoCloseable(M.blocking { pool.getConnection })
      _ <- shutdownDatabase(conn)
    } yield createTransactor(conn, strategy)
  }

  private def jdbcUrl(database: String) = show"jdbc:h2:mem:$database"

  private def shutdownDatabase[M[_]](conn: Resource[M, Connection]) = {
    // keep a connection open, when all connections are closed the database will be shutdown
    conn
  }

  private def createTransactor[M[_]](conn: Resource[M, Connection], strategy: Strategy)(implicit M: Sync[M]) = {
    Transactor[M, Unit]((), _ => conn, KleisliInterpreter[M].ConnectionInterpreter, strategy)
  }

}
