package doobie.mysql

import cats.effect.kernel.Async
import doobie.free.connection.ConnectionIO
import zio.Chunk
import zio.Task
import zio.ZIO
import zio.ZLayer
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpec
import zoobie.ConnectionPoolConfig
import zoobie.DatabaseError
import zoobie.Transactor
import zoobie.mysql.MySQLConnectionConfig

abstract class MySQLDatabaseSpec extends ZIOSpec[Transactor] { self =>

  protected implicit val instance: Async[Task] = MySQLDatabaseSpec.instance

  def transact[A](io: ConnectionIO[A]): ZIO[Transactor, DatabaseError, A] = {
    ZIO.serviceWithZIO[Transactor](_.run(io))
  }

  implicit class ConnectionIOExtension[A](c: ConnectionIO[A]) {
    def transact: ZIO[Transactor, DatabaseError, A] = self.transact(c)
  }

  override val bootstrap = MySQLDatabaseSpec.layer

  override def aspects = super.aspects ++ Chunk(
    TestAspect.samples(50), // default is 200
    TestAspect.timed,
    TestAspect.timeout(60.seconds),
    TestAspect.withLiveEnvironment,
  )
}
object MySQLDatabaseSpec {
  private implicit val instance: Async[Task] = zio.interop.catz.asyncInstance[Any]

  private val availableProcessors = Runtime.getRuntime.availableProcessors
  private val poolSize = (availableProcessors * 2).max(4)

  val connectionConfig = MySQLConnectionConfig(
    host = "localhost",
    database = "world",
    username = "root",
    password = "password",
    properties = Map(
      // args from solution 2a https://docs.oracle.com/cd/E17952_01/connector-j-8.0-en/connector-j-time-instants.html
      "preserveInstants" -> "true",
      "connectionTimeZone" -> "SERVER",
    ),
  )

  val config = ConnectionPoolConfig(
    name = "doobie",
    size = poolSize,
    queueSize = 1_000,
    maxConnectionLifetime = 10.minutes,
    validationTimeout = 1.second,
  )

  val layer: ZLayer[Any, Nothing, Transactor] = ZLayer.scoped[Any] {
    createTransactor
  }

  private def createTransactor = {
    zoobie.mysql.pool(connectionConfig, config).map { pool =>
      Transactor.fromPoolTransactional(pool)
    }
  }
}
