package doobie.postgres

import cats.effect.kernel.Async
import com.zaxxer.hikari.HikariDataSource
import doobie.free.connection.ConnectionIO
import doobie.hikari.HikariTransactor
import doobie.util.transactor.Transactor
import zio.Chunk
import zio.Task
import zio.ZIO
import zio.ZLayer
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpec

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

abstract class PostgresDatabaseSpec extends ZIOSpec[Transactor[Task]] { self =>

  protected implicit val instance: Async[Task] = PostgresDatabaseSpec.instance

  def transact[A](io: ConnectionIO[A]): ZIO[Transactor[Task], Throwable, A] = {
    ZIO.serviceWithZIO[Transactor[Task]](_.trans(instance)(io))
  }

  implicit class ConnectionIOExtension[A](c: ConnectionIO[A]) {
    def transact: ZIO[Transactor[Task], Throwable, A] = self.transact(c)
  }

  override val bootstrap = PostgresDatabaseSpec.layer

  override def aspects = super.aspects ++ Chunk(
    TestAspect.samples(50), // default is 200
    TestAspect.timed,
    TestAspect.timeout(45.seconds),
    TestAspect.withLiveEnvironment,
  )
}
object PostgresDatabaseSpec {
  private implicit val instance: Async[Task] = zio.interop.catz.asyncInstance[Any]

  private val availableProcessors = Runtime.getRuntime.availableProcessors
  private val poolSize = (availableProcessors * 2).max(4)

  val layer: ZLayer[Any, Nothing, Transactor[Task]] = ZLayer.scoped[Any] {
    createTransactor
  }

  private def createTransactor = for {
    _ <- ZIO.succeed(org.postgresql.Driver.register()).when(!org.postgresql.Driver.isRegistered)
    ds <- ZIO.fromAutoCloseable(ZIO.succeed(createDataSource))
    executor <- ZIO.acquireRelease(ZIO.succeed(Executors.newFixedThreadPool(poolSize))) { executor =>
      ZIO.attemptBlocking(executor.shutdown()).ignoreLogged
    }
  } yield {
    HikariTransactor.apply[Task](ds, ExecutionContext.fromExecutor(executor))
  }

  private def createDataSource = {
    val dataSource = new HikariDataSource
    dataSource.setJdbcUrl("jdbc:postgresql://localhost:5432/world")
    dataSource.setUsername("postgres")
    dataSource.setPassword("password")
    dataSource.setMinimumIdle(poolSize.min(4))
    dataSource.setMaximumPoolSize(poolSize)
    dataSource.setConnectionTimeout(15.seconds.toMillis)
    dataSource
  }
}
