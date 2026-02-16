package doobie.postgis

import doobie.postgres.PostgresDatabaseSpec
import zio.ZIO
import zio.ZLayer
import zoobie.ConnectionPool
import zoobie.Transactor

abstract class PostgisDatabaseSpec extends PostgresDatabaseSpec { self =>

  override val bootstrap = PostgisDatabaseSpec.layer
}
object PostgisDatabaseSpec {

  val layer: ZLayer[Any, Nothing, ConnectionPool & Transactor] = {
    import PostgresDatabaseSpec.config
    import PostgresDatabaseSpec.connectionConfig

    ZLayer.scoped[Any] {
      zoobie.postgres.postgis(connectionConfig, config)
    } >+> ZLayer.fromZIO {
      ZIO.serviceWith[ConnectionPool](Transactor.fromPoolTransactional(_))
    }
  }
}
