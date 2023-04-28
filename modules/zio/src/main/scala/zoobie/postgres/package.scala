package zoobie

import zio.Scope
import zio.ZIO

package object postgres {

  def pool(
    config: PostgreSQLConnectionConfig,
    poolConfig: ConnectionPoolConfig,
  ): ZIO[Scope, Nothing, ConnectionPool] = {
    ConnectionPool.create(createConnection(config), poolConfig)
  }

  def postgis(
    config: PostgreSQLConnectionConfig,
    poolConfig: ConnectionPoolConfig,
  ): ZIO[Scope, Nothing, ConnectionPool] = {

    val create = createConnection(config).map { conn =>
      /** see [[net.postgis.jdbc.DriverWrapper.TypesAdder80]] */
      conn.addDataType("box2d", classOf[net.postgis.jdbc.PGbox2d])
      conn.addDataType("box3d", classOf[net.postgis.jdbc.PGbox3d])
      conn.addDataType("geometry", classOf[net.postgis.jdbc.PGgeometryLW])
      conn.addDataType("geography", classOf[net.postgis.jdbc.PGgeographyLW])
      conn
    }

    ConnectionPool.create(create, poolConfig)
  }

  private def createConnection(config: PostgreSQLConnectionConfig) = ZIO.attemptBlocking {
    val host = new org.postgresql.util.HostSpec(config.host, config.port)
    new org.postgresql.jdbc.PgConnection(Array(host), config.props, config.url)
  }.mapError(DatabaseError.Connection(_))
}
