package doobie.postgis

import doobie.postgres.PostgresDatabaseSpec
import zio.ZLayer
import zoobie.Transactor

abstract class PostgisDatabaseSpec extends PostgresDatabaseSpec { self =>

  override val bootstrap = PostgisDatabaseSpec.layer
}
object PostgisDatabaseSpec {

  val layer: ZLayer[Any, Nothing, Transactor] = ZLayer.scoped[Any] {
    createTransactor
  }

  private def createTransactor = {
    import PostgresDatabaseSpec.config
    import PostgresDatabaseSpec.connectionConfig

    zoobie.postgres.postgis(connectionConfig, config).map { pool =>
      Transactor.fromPoolTransactional(pool)
    }
  }
}
