// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import doobie.util.transactor.Strategy
import doobie.util.transactor.Transactor
import org.h2.jdbcx.JdbcConnectionPool

package object h2 {

  type H2Transactor[M[_]] = Transactor.Aux[M, JdbcConnectionPool]

  object implicits
    extends Instances
    with syntax.ToH2TransactorOps

  def inMemory[M[_]](
    database: String,
    strategy: Strategy = Strategy.default,
  )(implicit M: Async[M]): Resource[M, Transactor[M]] = H2Helper.inMemory(database, strategy)

}
