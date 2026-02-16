// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.h2

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import doobie.util.transactor.Transactor
import org.h2.jdbcx.JdbcConnectionPool

object H2Transactor {

  /** Resource yielding a new H2Transactor. */
  def newH2Transactor[M[_]](
    url: String,
    user: String,
    pass: String,
  )(implicit M: Sync[M]): Resource[M, H2Transactor[M]] = {
    val alloc = M.delay(JdbcConnectionPool.create(url, user, pass))
    val free = (ds: JdbcConnectionPool) => M.delay(ds.dispose())
    Resource.make(alloc)(free).map { pool =>
      val connect = Resource.fromAutoCloseable(M.blocking(pool.getConnection))
      Transactor.catsEffect(pool, connect)
    }
  }

}
