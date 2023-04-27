package zoobie

import zio.Scope
import zio.ZIO

package object mysql {

  def pool(
    config: MySQLConnectionConfig,
    poolConfig: ConnectionPoolConfig,
  ): ZIO[Scope, Nothing, ConnectionPool] = for {
    driver <- ZIO.succeed(new com.mysql.cj.jdbc.NonRegisteringDriver())
    createConnection = ZIO.attemptBlocking {
      driver.connect(config.url, config.props)
    }.mapError(DatabaseError.Connection(_))
    pool <- ConnectionPool.create(createConnection, poolConfig)
  } yield pool
}
