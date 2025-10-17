package doobie.postgres

import doobie.free.connection.ConnectionIO
import zio.Chunk
import zio.ZIO
import zio.ZLayer
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpec
import zoobie.ConnectionPool
import zoobie.ConnectionPoolConfig
import zoobie.DatabaseError
import zoobie.Transactor
import zoobie.postgres.PostgreSQLConnectionConfig

abstract class PostgresDatabaseSpec extends ZIOSpec[ConnectionPool & Transactor] { self =>

  def transact[A](io: ConnectionIO[A]): ZIO[Transactor, DatabaseError, A] = {
    ZIO.serviceWithZIO[Transactor](_.run(io))
  }

  implicit class ConnectionIOExtension[A](c: ConnectionIO[A]) {
    def transact: ZIO[Transactor, DatabaseError, A] = self.transact(c)
  }

  override val bootstrap = PostgresDatabaseSpec.layer

  override def aspects = super.aspects ++ Chunk(
    TestAspect.samples(50), // default is 200
    TestAspect.timed,
    TestAspect.timeout(60.seconds),
    TestAspect.withLiveEnvironment,
  )
}
object PostgresDatabaseSpec {

  private val availableProcessors = Runtime.getRuntime.availableProcessors
  private val poolSize = (availableProcessors * 2).max(4)

  val connectionConfig = PostgreSQLConnectionConfig(
    host = "localhost",
    database = "world",
    username = "postgres",
    password = "password",
    applicationName = "doobie",
  )

  val config = ConnectionPoolConfig(
    name = "doobie",
    size = poolSize,
    queueSize = 1_000,
    maxConnectionLifetime = 10.minutes,
    validationTimeout = 1.second,
  )

  val layer: ZLayer[Any, Nothing, ConnectionPool & Transactor] = {
    ZLayer.scoped[Any] {
      zoobie.postgres.pool(connectionConfig, config)
    } >+> ZLayer.fromZIO(ZIO.serviceWith[ConnectionPool] { pool =>
      Transactor.fromPoolTransactional(pool)
    })
  }
}
