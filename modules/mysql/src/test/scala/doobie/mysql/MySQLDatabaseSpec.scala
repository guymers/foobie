package doobie.mysql

import cats.effect.kernel.Async
import cats.syntax.foldable.*
import cats.syntax.show.*
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
import scala.collection.immutable.SortedMap
import scala.concurrent.ExecutionContext

abstract class MySQLDatabaseSpec extends ZIOSpec[Transactor[Task]] { self =>

  protected implicit val instance: Async[Task] = MySQLDatabaseSpec.instance

  def transact[A](io: ConnectionIO[A]): ZIO[Transactor[Task], Throwable, A] = {
    ZIO.serviceWithZIO[Transactor[Task]](_.trans(instance)(io))
  }

  implicit class ConnectionIOExtension[A](c: ConnectionIO[A]) {
    def transact: ZIO[Transactor[Task], Throwable, A] = self.transact(c)
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

  val layer: ZLayer[Any, Nothing, Transactor[Task]] = ZLayer.scoped[Any] {
    createTransactor
  }

  private def createTransactor = for {
    _ <- ZIO.succeed(new com.mysql.cj.jdbc.Driver())
    ds <- ZIO.fromAutoCloseable(ZIO.succeed(createDataSource))
    executor <- ZIO.acquireRelease(ZIO.succeed(Executors.newFixedThreadPool(poolSize))) { executor =>
      ZIO.attemptBlocking(executor.shutdown()).ignoreLogged
    }
  } yield {
    HikariTransactor.apply[Task](ds, ExecutionContext.fromExecutor(executor))
  }

  private def createDataSource = {
    val params = SortedMap(
      // args from solution 2a https://docs.oracle.com/cd/E17952_01/connector-j-8.0-en/connector-j-time-instants.html
      "preserveInstants" -> "true",
      "connectionTimeZone" -> "SERVER",
    )
    val paramStr = params.toList.map { case (k, v) => show"$k=$v" }.mkString_("&")
    val url = show"jdbc:mysql://localhost:3306/world?$paramStr"

    val dataSource = new HikariDataSource
    dataSource.setJdbcUrl(url)
    dataSource.setUsername("root")
    dataSource.setPassword("password")
    dataSource.setMinimumIdle(poolSize.min(4))
    dataSource.setMaximumPoolSize(poolSize)
    dataSource.setConnectionTimeout(15.seconds.toMillis)
    dataSource
  }
}
