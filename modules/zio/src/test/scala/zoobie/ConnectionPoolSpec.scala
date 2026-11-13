package zoobie

import zio.Chunk
import zio.Exit
import zio.Promise
import zio.Ref
import zio.Schedule
import zio.Scope
import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.TestClock
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zoobie.stub.StubConnection

import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.util.concurrent.TimeUnit

object ConnectionPoolSpec extends ZIOSpecDefault {

  private val config = ConnectionPoolConfig(
    name = "pool",
    size = 5,
    queueSize = 3,
    maxConnectionLifetime = 60.seconds,
    validationTimeout = 10.seconds,
    alwaysInvalidateOnFailure = false,
  )

  override val spec = suite("ConnectionPool")(
    test("connection fails to acquire") {
      // FIXME this test only passes due to being lucky with the connection order
      for {
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
      val secondTime = zio.Clock.nanoTime.map(_ / 1_000_000_000).map(_.toInt)

      for {
        createdRef <- Ref.Synchronized.make(Chunk.empty[StubConnection])
        create = createdRef.modifyZIO { created =>
          secondTime.map { now =>
            val invalidAfter = now + config.maxConnectionLifetime.toSeconds
            val c = new StubConnection(timeout => {
              timeout == config.validationTimeout.toMillis || timeout <= invalidAfter
            })
            (c, created :+ c)
          }
        }
        pool <- ConnectionPool.create(create, config)

        _ <- ZIO.foreachDiscard((1 to config.size).toList)(_ => ZIO.scoped[Any](pool.get))
        createdInitial <- createdRef.get

        _ <- TestClock.adjust(config.maxConnectionLifetime)
        _ <- TestClock.adjust(1.second)
        validAfterAllExceedMaxLifetime <- ZIO.scoped[Any] {
          for {
            c <- pool.get
            now <- secondTime
          } yield c.isValid(now)
        }

        _ <- ZIO.foreachDiscard((1 to config.size).toList)(_ => ZIO.scoped[Any](pool.get))
        createdRefreshed <- createdRef.get
      } yield {
        assertTrue(createdInitial.size == 5) &&
        assertTrue(createdInitial.forall(_.isClosed)) &&
        assertTrue(validAfterAllExceedMaxLifetime) &&
        assertTrue(createdRefreshed.size == 10)
      }
    },
    test("connections are refreshed after failures") {
      def run(alwaysInvalidateOnFailure: Boolean) = for {
        tuple <- recordingPool(config.copy(alwaysInvalidateOnFailure = alwaysInvalidateOnFailure)) { _ =>
          new StubConnection(_ => true)
        }
        (pool, created) = tuple
        result <- ZIO.scoped[Any] {
          pool.get *> ZIO.fail(new RuntimeException("boom"))
        }.either
        created <- created
      } yield {
        (result, created.count(_.isClosed))
      }

      for {
        runWithFlag <- run(alwaysInvalidateOnFailure = true)
        (resultWithFlag, numClosedWithFlag) = runWithFlag
        runWithoutFlag <- run(alwaysInvalidateOnFailure = false)
        (resultWithoutFlag, numClosedWithoutFlag) = runWithoutFlag
      } yield {
        assertTrue(resultWithFlag.isLeft) &&
        assertTrue(numClosedWithFlag == 1) &&
        assertTrue(resultWithoutFlag.isLeft) &&
        assertTrue(numClosedWithoutFlag == 0)
      }
    },
    test("connection state is reset when returned to the pool") {
      for {
        _ <- ZIO.unit
        create = ZIO.succeed(new StubConnection(_ => true))
        pool <- ConnectionPool.create(create, config.copy(size = 1))
        _ <- ZIO.scoped[Any] {
          pool.get.map { c =>
            c.setAutoCommit(false)
            c.setReadOnly(true)
          }
        }
        state <- ZIO.scoped[Any] {
          pool.get.map(c => (c.getAutoCommit, c.isReadOnly))
        }
      } yield {
        assertTrue(state == ((true, false)))
      }
    },
    test("connection is invalidated if state can not be reset") {
      def run(closeEarly: Boolean) = for {
        tuple <- recordingPool() { i =>
          if (i == 0) StubConnection.failingReset() else new StubConnection(_ => true)
        }
        (pool, created) = tuple
        closed <- ZIO.scoped[Any](pool.get.flatMap(c => ZIO.attempt(c.close()).either.when(closeEarly)))
        _ <- ZIO.scoped[Any](pool.get)
        created <- created
      } yield (closed, created.map(_.isClosed))

      for {
        notClosed <- run(closeEarly = false)
        (notClosedResult, notClosedConnections) = notClosed
        closedEarly <- run(closeEarly = true)
        (closedEarlyResult, closedEarlyConnections) = closedEarly
      } yield {
        assertTrue(notClosedResult.isEmpty) &&
        assertTrue(notClosedConnections == Chunk(true, false)) &&
        assertTrue(closedEarlyResult.exists(_.isLeft)) &&
        assertTrue(closedEarlyConnections == Chunk(true, false))
      }
    },
    test("an aborted connection is invalidated") {
      for {
        tuple <- recordingPool() { _ =>
          new StubConnection(_ => true) {
            override def abort(executor: java.util.concurrent.Executor): Unit = ()
          }
        }
        (pool, created) = tuple
        _ <- ZIO.scoped[Any](pool.get.map(_.abort((r: Runnable) => r.run())))
        _ <- ZIO.scoped[Any](pool.get)
        created <- created
      } yield {
        assertTrue(created.map(_.isClosed) == Chunk(true, false))
      }
    },
    test("connection can not be used after it is returned to the pool") {
      for {
        tuple <- recordingPool()(_ => new StubConnection(_ => true))
        (pool, _) = tuple
        leaked <- ZIO.scoped[Any](pool.get)
        result <- ZIO.attempt(leaked.prepareStatement("SELECT 1")).exit
      } yield {
        assertTrue(leaked.isClosed) &&
        assertTrue(result.isFailure)
      }
    },
    test("an open transaction is rolled back when returned to the pool") {
      def run(fail: Boolean) = for {
        tuple <- recordingPool()(_ => new StubConnection(_ => true))
        (pool, created) = tuple
        _ <- ZIO.scoped[Any] {
          pool.get.flatMap { c =>
            c.setAutoCommit(false)
            ZIO.fail(new RuntimeException("boom")).when(fail)
          }
        }.either
        created <- created
      } yield created

      for {
        onSuccess <- run(fail = false)
        onFailure <- run(fail = true)
      } yield {
        assertTrue(onSuccess.size == 1) &&
        assertTrue(onFailure.size == 1) &&
        assertTrue((onSuccess ++ onFailure).forall(c => c.numRollbacks == 1 && c.numCommits == 0))
      }
    },
    test("a connection that does not respond while being reset is replaced") {
      for {
        tuple <- recordingPool(config.copy(size = 1, validationTimeout = 200.millis)) { i =>
          lazy val dead: StubConnection = new StubConnection(_ => true) {
            // a dead connection only fails a call once the network timeout passes. without the
            // timeout this returns, so the connection would be reused and the test fails
            override def clearWarnings(): Unit = {
              if (dead.getNetworkTimeout == 200) throw new java.sql.SQLException("timed out")
            }
          }
          if (i == 0) dead else new StubConnection(_ => true)
        }
        (pool, created) = tuple
        returned <- ZIO.scoped[Any](pool.get).exit
        next <- ZIO.scoped[Any](pool.get).exit
        created <- created
      } yield {
        assertTrue(returned.isSuccess) &&
        assertTrue(next.isSuccess) &&
        assertTrue(created.map(_.isClosed) == Chunk(true, false))
      }
    },
    test("after a failure the connection is validated once it has been reset") {
      def run(valid: Boolean) = for {
        rolledBackBeforeValidation <- ZIO.succeed(new java.util.concurrent.atomic.AtomicReference(Option.empty[Boolean]))
        tuple <- recordingPool() { _ =>
          lazy val connection: StubConnection = new StubConnection(_ => true) {
            override def isValid(timeout: Int): Boolean = {
              rolledBackBeforeValidation.set(Some(connection.numRollbacks == 1))
              valid
            }
          }
          connection
        }
        (pool, created) = tuple
        _ <- ZIO.scoped[Any] {
          pool.get.flatMap { c =>
            c.setAutoCommit(false)
            ZIO.fail(new RuntimeException("boom"))
          }
        }.either
        _ <- ZIO.scoped[Any](pool.get)
        order = rolledBackBeforeValidation.get
        created <- created
      } yield (order, created.map(_.isClosed))

      for {
        whenValid <- run(valid = true)
        whenInvalid <- run(valid = false)
      } yield {
        assertTrue(whenValid == ((Some(true), Chunk(false)))) &&
        assertTrue(whenInvalid == ((Some(true), Chunk(true, false))))
      }
    },
    test("timeouts are converted for JDBC rounded up and capped") {
      def t(d: Duration) = (
        ConnectionPool.jdbcTimeout(d, TimeUnit.SECONDS),
        ConnectionPool.jdbcTimeout(d, TimeUnit.MILLISECONDS),
      )

      assertTrue(t(Duration.ZERO) == ((0, 0))) &&
      assertTrue(t(Duration.ofMillis(-5)) == ((0, 0))) &&
      assertTrue(t(Duration.ofSeconds(-5)) == ((0, 0))) &&
      assertTrue(t(Duration.ofNanos(1)) == ((1, 1))) &&
      assertTrue(t(Duration.ofMillis(200)) == ((1, 200))) &&
      assertTrue(t(Duration.ofMillis(1500)) == ((2, 1500))) &&
      assertTrue(t(Duration.ofSeconds(Int.MaxValue.toLong / 1000 + 1)) == ((Int.MaxValue / 1000 + 1, Int.MaxValue))) &&
      assertTrue(t(Duration.ofSeconds(Long.MaxValue)) == ((Int.MaxValue, Int.MaxValue)))
    },
    test("a validation timeout of zero resets with the existing network timeout") {
      val during = new java.util.concurrent.atomic.AtomicInteger(-1)
      for {
        tuple <- recordingPool(config.copy(size = 1, validationTimeout = 0.seconds)) { _ =>
          lazy val connection: StubConnection = new StubConnection(_ => true) {
            override def clearWarnings(): Unit = during.set(connection.getNetworkTimeout)
          }
          connection.setNetworkTimeout((r: Runnable) => r.run(), 5000)
          connection
        }
        (pool, created) = tuple
        _ <- ZIO.scoped[Any](pool.get)
        _ <- ZIO.scoped[Any](pool.get)
        created <- created
      } yield {
        assertTrue(during.get == 5000) &&
        assertTrue(created.size == 1) &&
        assertTrue(created.forall(_.getNetworkTimeout == 5000))
      }
    },
  ) @@ TestAspect.timeout(15.seconds)

  /**
   * Creates a pool that records every connection it creates.
   *
   * @param connection
   *   creates the n-th connection, counting from zero
   * @return
   *   the pool, and an effect that returns the connections created so far
   */
  private def recordingPool(poolConfig: ConnectionPoolConfig = config.copy(size = 1))(connection: Int => StubConnection) = for {
    createdRef <- Ref.make(Chunk.empty[StubConnection])
    create = createdRef.modify { created =>
      val c = connection(created.size)
      (c, created :+ c)
    }
    pool <- ConnectionPool.create(create, poolConfig)
  } yield (pool, createdRef.get)
}
