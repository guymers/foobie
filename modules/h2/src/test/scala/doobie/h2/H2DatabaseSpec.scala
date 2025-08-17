package doobie.h2

import cats.effect.kernel.Sync
import doobie.free.connection.ConnectionIO
import doobie.util.transactor.Transactor
import zio.Chunk
import zio.Task
import zio.ZIO
import zio.ZLayer
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpec

abstract class BaseH2DatabaseSpec extends ZIOSpec[Transactor[Task]] { self =>

  def transact[A](io: ConnectionIO[A]): ZIO[Transactor[Task], Throwable, A] = {
    ZIO.serviceWithZIO[Transactor[Task]](_.run(io))
  }

  implicit class ConnectionIOExtension[A](c: ConnectionIO[A]) {
    def transact: ZIO[Transactor[Task], Throwable, A] = self.transact(c)
  }

  override val bootstrap = H2DatabaseSpec.layer

  override def aspects = super.aspects ++ Chunk(
    TestAspect.samples(50), // default is 200
    TestAspect.timed,
    TestAspect.timeout(60.seconds),
    TestAspect.withLiveEnvironment,
  )
}

abstract class H2DatabaseSpec extends BaseH2DatabaseSpec {
  override val bootstrap = H2DatabaseSpec.layer
}
object H2DatabaseSpec {
  private[h2] val instance: Sync[Task] = zio.interop.catz.asyncInstance[Any]

  private val availableProcessors = Runtime.getRuntime.availableProcessors
  private[h2] val maxConnections = (availableProcessors * 2).max(4)

  val layer = ZLayer.scoped[Any](create("test_h2"))

  def create(database: String) = {
    import zio.interop.catz.zioResourceSyntax
    H2Helper.inMemoryPooled(database, maxConnections = H2DatabaseSpec.maxConnections)(instance).toScopedZIO
  }
}
