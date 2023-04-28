package zoobie

import zio.Chunk
import zio.Exit
import zio.Promise
import zio.Ref
import zio.Schedule
import zio.Scope
import zio.ZIO
import zio.durationInt
import zio.durationLong
import zio.test.TestAspect
import zio.test.TestClock
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zoobie.stub.StubConnection

import java.sql.SQLTransientConnectionException

object ConnectionPoolSpec extends ZIOSpecDefault {

  private val config = ConnectionPoolConfig(
    name = "pool",
    size = 5,
    queueSize = 3,
    maxConnectionLifetime = 60.seconds,
    validationTimeout = 10.seconds,
  )

  override val spec = suite("ConnectionPool")(
    test("connection fails to acquire") {
      for {
        _ <- ZIO.unit
        ref <- Ref.make(1)
        create = ref.modify { i =>
          val conn = if (i % 2 == 0) {
            Left(DatabaseError.Connection.Transient(new SQLTransientConnectionException))
          } else {
            Right(new StubConnection(_ => true))
          }
          (conn, i + 1)
        }.flatMap(ZIO.fromEither(_))
        results <- ZIO.scoped[Any](for {
          pool <- ConnectionPool.create(create, config)
          r1 <- ZIO.scoped[Any] { pool.get }.either
          r2 <- ZIO.scoped[Any] { pool.get }.either
          r3 <- ZIO.scoped[Any] { pool.get }.either
          r4 <- ZIO.scoped[Any] {
            pool.get.retry(Schedule.recurWhile[DatabaseError.Connection]({
              case _: DatabaseError.Connection.Transient => true
              case _ => false
            }))
          }.either
        } yield {
          (r1, r2, r3, r4)
        })
      } yield {
        val (r1, r2, r3, r4) = results
        assertTrue(r1.isRight) &&
        assertTrue(r2.isLeft) &&
        assertTrue(r3.isRight) &&
        assertTrue(r4.isRight)
      }
    },
    test("closing the pool closes all connections") {
      for {
        _ <- ZIO.unit
        connections = (1 to config.size).map(_ => new StubConnection(_ => true))
        ref <- Ref.make(connections)
        create = ref.modify(conns => (conns.head, conns.drop(1)))
        numClosed <- ZIO.scoped[Any](for {
          pool <- ConnectionPool.create(create, config)
          _ <- ZIO.foreach((1 to (config.size * 2)).toList) { _ =>
            ZIO.scoped[Any] { pool.get }
          }
        } yield {
          connections.count(_.isClosed)
        })
      } yield {
        assertTrue(numClosed == 0) &&
        assertTrue(connections.forall(_.isClosed))
      }
    },
    test("graceful shutdown") {
      for {
        _ <- ZIO.unit
        connections = (1 to config.size).map(_ => new StubConnection(_ => true))
        ref <- Ref.make(connections)
        create = ref.modify(conns => (conns.head, conns.drop(1)))
        scope <- Scope.make
        pool <- scope.extend[Any](ConnectionPool.create(create, config.copy(size = 2)))
        running1 <- ZIO.scoped[Any] { pool.get *> ZIO.sleep(1.second) }.as(1).fork
        running2 <- ZIO.scoped[Any] { pool.get *> ZIO.sleep(2.seconds) }.as(2).fork
        waiting1 <- ZIO.scoped[Any] { pool.get *> ZIO.sleep(3.seconds) }.as(3).fork
        _ <- scope.close(Exit.unit)
        running1Result <- running1.await
        running2Result <- running2.await
        waiting1Result <- waiting1.await
      } yield {
        assertTrue(running1Result.isInterruptedOnly) &&
        assertTrue(running2Result.isInterruptedOnly) &&
        assertTrue(waiting1Result.isInterruptedOnly)
      }
    },
    test("attempts wait until capacity is available") {
      val create = ZIO.succeed(new StubConnection(_ => true))
      for {
        pool <- ConnectionPool.create(create, config)
        _ <- ZIO.foreachDiscard((1 until config.size).toList)(_ => pool.get)

        // acquire the last available connection
        c0 <- for {
          acquired <- Promise.make[Nothing, Unit]
          c <- ZIO.scoped[Any] {
            pool.get.tap(_ => acquired.succeed(())).flatMap(_ => ZIO.sleep(10.seconds))
          }.fork
          _ <- acquired.await
        } yield c

        // queue three requests
        acquiredRef <- Ref.make(Set.empty[Int])
        c1 <- ZIO.scoped[Any](pool.get.flatMap(_ => acquiredRef.update(_ + 1))).fork
        c2 <- ZIO.scoped[Any](pool.get.flatMap(_ => acquiredRef.update(_ + 2))).fork
        c3 <- ZIO.scoped[Any](pool.get.flatMap(_ => acquiredRef.update(_ + 3))).fork

        _ <- TestClock.adjust(5.seconds)
        c4 <- ZIO.scoped[Any](pool.get.flatMap(_ => acquiredRef.update(_ + 4))).fork
        c4Rejected <- c4.await
        _ <- c2.interrupt
        _ <- TestClock.adjust(5.seconds)
        _ <- c0.join <&> c1.join <&> c3.join

        _ <- ZIO.scoped[Any](pool.get.flatMap(_ => acquiredRef.update(_ + 11)))

        acquired <- acquiredRef.get
      } yield {
        assertTrue(acquired == Set(1, 3, 11)) &&
        assertTrue(c4Rejected == Exit.fail(DatabaseError.Connection.Rejected(3)))
      }
    },
    test("connections are refreshed after lifetime") {
      for {
        createdRef <- Ref.make(Chunk.empty[StubConnection])
        create = createdRef.modify { created =>
          val c = new StubConnection(_ => true)
          (c, created :+ c)
        }
        pool <- ConnectionPool.create(create, config)

        _ <- ZIO.foreachDiscard((1 to config.size).toList)(_ => ZIO.scoped(pool.get))
        createdInitial <- createdRef.get

        _ <- TestClock.adjust((config.maxConnectionLifetime.toNanos * 1.11).toLong.nanos) // jittered 0.9-1.1

        _ <- ZIO.foreachDiscard((1 to config.size).toList)(_ => ZIO.scoped(pool.get))
        createdRefreshed <- createdRef.get
      } yield {
        assertTrue(createdInitial.size == 5) &&
        assertTrue(createdInitial.forall(_.isClosed)) &&
        assertTrue(createdRefreshed.size == 10)
      }
    },
  ) @@ TestAspect.timeout(15.seconds)
}
