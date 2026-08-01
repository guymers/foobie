// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.h2

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.show.*
import doobie.free.KleisliInterpreter
import doobie.util.ExecutionContexts
import doobie.util.transactor.Strategy
import doobie.util.transactor.Transactor
import org.h2.jdbcx.JdbcConnectionPool

import java.sql.Connection
import scala.concurrent.ExecutionContext

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

    for {
      executionContext <- ExecutionContexts.cachedThreadPool[M]
      keepAlive = Resource.fromAutoCloseable(M.blocking { driver.connect(url, props) })
      conn = Resource.fromAutoCloseable(M.blocking { driver.connect(url, props) })
      _ <- shutdownDatabase(keepAlive)
    } yield createTransactor(conn, strategy, executionContext)
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
      executionContext <- ExecutionContexts.fixedThreadPool[M](maxConnections)
      keepAlive = Resource.fromAutoCloseable(M.blocking { pool.getConnection })
      conn = Resource.fromAutoCloseable(M.blocking { pool.getConnection })
      _ <- shutdownDatabase(keepAlive)
    } yield createTransactor(conn, strategy, executionContext)
  }

  private def jdbcUrl(database: String) = show"jdbc:h2:mem:$database"

  private def shutdownDatabase[M[_]](conn: Resource[M, Connection]) = {
    // keep a connection open, when all connections are closed the database will be shutdown
    conn
  }

  private def createTransactor[M[_]](
    conn: Resource[M, Connection],
    strategy: Strategy,
    executionContext: ExecutionContext,
  )(implicit M: Sync[M]) =
    M match {
      case async: Async[M @unchecked] =>
        Transactor[M, Unit](
          (),
          _ => conn,
          KleisliInterpreter.onBlockingThread[M].ConnectionInterpreter,
          strategy,
          async.evalOnK(executionContext),
        )
      case _ =>
        Transactor[M, Unit]((), _ => conn, KleisliInterpreter[M].ConnectionInterpreter, strategy)
    }

}
