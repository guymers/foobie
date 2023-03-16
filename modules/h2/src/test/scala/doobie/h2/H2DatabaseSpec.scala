package doobie.h2

import cats.effect.kernel.Async
import doobie.free.connection.ConnectionIO
import doobie.util.transactor.Transactor
import zio.Chunk
import zio.Task
import zio.ZIO
import zio.ZLayer
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpec

abstract class BaseH2DatabaseSpec(database: String) extends ZIOSpec[Transactor[Task]] { self =>
  protected implicit val instance: Async[Task] = zio.interop.catz.asyncInstance[Any]

  def transact[A](io: ConnectionIO[A]): ZIO[Transactor[Task], Throwable, A] = {
    ZIO.serviceWithZIO[Transactor[Task]](_.trans(instance)(io))
  }

  implicit class ConnectionIOExtension[A](c: ConnectionIO[A]) {
    def transact: ZIO[Transactor[Task], Throwable, A] = self.transact(c)
  }

  override val bootstrap = ZLayer.scoped {
    import zio.interop.catz.zioResourceSyntax
    H2Helper.inMemory(database)(instance).toScopedZIO
  }

  override val aspects = super.aspects ++ Chunk(
    TestAspect.timeout(30.seconds),
    TestAspect.withLiveEnvironment,
  )
}

abstract class H2DatabaseSpec extends BaseH2DatabaseSpec("test_h2")
