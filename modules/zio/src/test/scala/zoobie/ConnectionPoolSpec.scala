package zoobie

import zio.Chunk
import zio.Exit
import zio.Fiber
import zio.LogLevel
import zio.Promise
import zio.Ref
import zio.Schedule
import zio.Scope
import zio.Trace
import zio.ZIO
import zio.durationInt
import zio.test.Live
import zio.test.TestAspect
import zio.test.TestClock
import zio.test.ZIOSpecDefault
import zio.test.ZTestLogger
import zio.test.assertTrue
import zoobie.stub.StubConnection

import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    suite("connection creation failures")(
      test("a failure to create a connection is returned to the request") {
        for {
          failing <- Ref.make(true)
          error = DatabaseError.Connection.Transient(new SQLTransientConnectionException)
          create = failing.get.flatMap { fail =>
            if (fail) {
              ZIO.fail(error)
            } else {
              ZIO.succeed(new StubConnection(_ => true))
            }
          }
          results <- ZIO.scoped[Any](for {
            pool <- ConnectionPool.create(create, config)
            failed <- ZIO.scoped[Any] { pool.get }.either
            _ <- failing.set(false)
            recovered <- ZIO.scoped[Any](pool.get).either
          } yield (failed, recovered))
          logged <- ZTestLogger.logOutput
        } yield {
          val (failed, recovered) = results
          assertTrue(failed == Left(error)) &&
          assertTrue(recovered.isRight) &&
          assertTrue(!logged.exists(_.message() == "Could not create connection"))
        }
      },
      test("a failure to create a connection releases pool capacity") {
        for {
          attempts <- Ref.make(0)
          create = attempts.updateAndGet(_ + 1).flatMap { i =>
            if (i <= 2) {
              ZIO.fail(DatabaseError.Connection.Transient(new SQLTransientConnectionException))
            } else {
              ZIO.succeed(new StubConnection(_ => true))
            }
          }
          pool <- ConnectionPool.create(create, config.copy(size = 1))
          result <- ZIO.scoped[Any] {
            pool.get.retry(Schedule.recurWhile[DatabaseError.Connection] {
              case _: DatabaseError.Connection.Transient => true
              case _ => false
            })
          }.either
          numAttempts <- attempts.get
        } yield {
          assertTrue(result.isRight) &&
          assertTrue(numAttempts == 3)
        }
      },
      test("a defect while creating a connection releases pool capacity") {
        val defect = new RuntimeException("boom")
        for {
          attempts <- Ref.make(0)
          create = attempts.updateAndGet(_ + 1).flatMap { i =>
            if (i == 1) ZIO.die(defect)
            else ZIO.succeed(new StubConnection(_ => true))
          }
          pool <- ConnectionPool.create(create, config.copy(size = 1))
          failed <- ZIO.scoped[Any](pool.get).exit
          recovered <- ZIO.scoped[Any](pool.get).exit
          numAttempts <- attempts.get
          logged <- ZTestLogger.logOutput
        } yield {
          assertTrue(failed.causeOption.flatMap(_.dieOption).contains(defect)) &&
          assertTrue(recovered.isSuccess) &&
          assertTrue(numAttempts == 2) &&
          assertTrue(!logged.exists(_.message() == "Could not create connection"))
        }
      },
    ),
    suite("shutdown")(
      test("closing the pool closes all idle connections") {
        for {
          _ <- ZIO.unit
          connections = (1 to config.size).map(_ => new StubConnection(_ => true))
          ref <- Ref.make(connections)
          create = ref.modify(conns => (conns.head, conns.drop(1)))
          numClosed <- ZIO.scoped[Any](for {
            pool <- ConnectionPool.create(create, config)
            // Check out all the connections together and return them, then do it again. the second
            // round has to reuse the connections from the first, `create` has no more to give.
            _ <- ZIO.foreachDiscard(1 to 2) { _ =>
              ZIO.scoped[Any](ZIO.foreachDiscard(1 to config.size)(_ => pool.get))
            }
          } yield {
            connections.count(_.isClosed)
          })
        } yield {
          assertTrue(numClosed == 0) &&
          assertTrue(connections.forall(_.isClosed))
        }
      },
      test("graceful shutdown waits for connections in use and interrupts waiting requests") {
        for {
          _ <- ZIO.unit
          connections = (1 to config.size).map(_ => new StubConnection(_ => true))
          ref <- Ref.make(connections)
          create = ref.modify(conns => (conns.head, conns.drop(1)))
          scope <- Scope.make
          pool <- scope.extend[Any](ConnectionPool.create(create, config.copy(name = "graceful-shutdown", size = 2)))
          held1 <- Promise.make[Nothing, Unit]
          held2 <- Promise.make[Nothing, Unit]
          running1 <- ZIO.scoped[Any] { pool.get *> held1.succeed(()) *> ZIO.sleep(1.second) }.as(1).fork
          running2 <- ZIO.scoped[Any] { pool.get *> held2.succeed(()) *> ZIO.sleep(2.seconds) }.as(2).fork
          _ <- held1.await *> held2.await
          waiting1 <- ZIO.scoped[Any] { pool.get *> ZIO.sleep(3.seconds) }.as(3).fork
          _ <- queuedRequests("graceful-shutdown", 1)
          closing <- scope.close(Exit.unit).fork
          _ <- shutdownWaiting(pool, closing)
          closeCompletedEarly <- closing.poll
          closedEarly = connections.exists(_.isClosed)
          // New requests fail while shutdown waits for connections in use.
          afterClose <- ZIO.scoped[Any](pool.get).exit
          _ <- TestClock.adjust(2.seconds)
          running1Result <- running1.await
          running2Result <- running2.await
          waiting1Result <- waiting1.await
          closingResult <- closing.await
        } yield {
          assertTrue(closeCompletedEarly.isEmpty) &&
          assertTrue(!closedEarly) &&
          assertTrue(running1Result == Exit.succeed(1)) &&
          assertTrue(running2Result == Exit.succeed(2)) &&
          assertTrue(waiting1Result.isInterruptedOnly) &&
          assertTrue(afterClose == Exit.fail(DatabaseError.Connection.Closed())) &&
          assertTrue(closingResult == Exit.unit) &&
          assertTrue(connections.take(2).forall(_.isClosed))
        }
      },
      test("shutdown waits for connection creation without interrupting it") {
        for {
          connecting <- Promise.make[Nothing, Unit]
          opened <- Promise.make[Nothing, Unit]
          interrupted <- Ref.make(false)
          connection = new StubConnection(_ => true)
          // creation could be cancelled, but the pool never interrupts it
          create = (connecting.succeed(()) *> opened.await.as(connection)).interruptible
            .onInterrupt(interrupted.set(true))
          scope <- Scope.make
          pool <- scope.extend[Any](ConnectionPool.create(create, config.copy(size = 1)))
          request <- ZIO.scoped[Any](pool.get).fork
          _ <- connecting.await
          closing <- scope.close(Exit.unit).fork
          _ <- shutdownWaiting(pool, closing)
          closeCompletedEarly <- closing.poll
          _ <- opened.succeed(())
          requestResult <- request.await
          closingResult <- closing.await
          wasInterrupted <- interrupted.get
        } yield {
          assertTrue(closeCompletedEarly.isEmpty) &&
          assertTrue(requestResult.isSuccess) &&
          assertTrue(closingResult == Exit.unit) &&
          assertTrue(!wasInterrupted, connection.isClosed)
        }
      },
      suite("blocking")({
        def blockingConnectShutdown(abandonRequest: Boolean) = {
          val connecting = new CountDownLatch(1)
          val proceed = new CountDownLatch(1)
          val closed = new AtomicInteger(0)
          val connection = new StubConnection(_ => true) {
            override def close(): Unit = {
              val _ = closed.incrementAndGet()
              super.close()
            }
          }
          for {
            scope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
            _ <- ZIO.addFinalizer(ZIO.succeed(proceed.countDown()))
            // Match the built-in JDBC drivers: no explicit interruption mask around connect.
            create = ZIO.attemptBlocking {
              connecting.countDown()
              if (!proceed.await(10, TimeUnit.SECONDS)) throw new SQLException("connect gate timed out")
              connection
            }.mapError(DatabaseError.Connection(_))
            pool <- scope.extend[Any](ConnectionPool.create(create, config.copy(size = 1)))
            acquired <- Ref.make(false)
            request <- ZIO.scoped[Any](pool.get *> acquired.set(true)).fork
            started <- ZIO.attemptBlocking(connecting.await(10, TimeUnit.SECONDS))
            _ <- request.interrupt.when(abandonRequest)
            closing <- scope.close(Exit.unit).fork
            _ <- shutdownWaiting(pool, closing)
            closeCompletedEarly <- closing.poll
            afterClose <- ZIO.scoped[Any](pool.get).exit
            _ <- ZIO.succeed(proceed.countDown())
            requestResult <- request.await
            closingResult <- closing.await
            received <- acquired.get
          } yield {
            assertTrue(started) &&
            assertTrue(closeCompletedEarly.isEmpty) &&
            assertTrue(afterClose == Exit.fail(DatabaseError.Connection.Closed())) &&
            assertTrue(requestResult.isInterruptedOnly == abandonRequest) &&
            assertTrue(received == !abandonRequest) &&
            assertTrue(closingResult == Exit.unit) &&
            assertTrue(connection.isClosed, closed.get() == 1)
          }
        }

        test("shutdown preserves a blocking connect until its connection is returned") {
          blockingConnectShutdown(abandonRequest = false)
        } ::
        test("shutdown closes a blocking connect's connection after its request is interrupted") {
          blockingConnectShutdown(abandonRequest = true)
        } :: Nil
      }),
      test("a request to a closed pool fails with a typed error") {
        for {
          scope <- Scope.make
          pool <- scope.extend[Any](ConnectionPool.create(ZIO.succeed(new StubConnection(_ => true)), config))
          _ <- scope.close(Exit.unit)
          result <- ZIO.scoped[Any](pool.get).exit
        } yield {
          assertTrue(result == Exit.fail(DatabaseError.Connection.Closed()))
        }
      },
      test("shutdown interrupts a nested request waiting for a permit its own fiber holds") {
        for {
          holding <- Promise.make[Nothing, Unit]
          scope <- Scope.make
          pool <- scope.extend[Any](ConnectionPool.create(ZIO.succeed(new StubConnection(_ => true)), config.copy(name = "nested-request", size = 1)))
          // the nested request can only get a permit once the outer one is returned
          nested <- ZIO.scoped[Any](pool.get *> holding.succeed(()) *> ZIO.scoped[Any](pool.get)).fork
          _ <- holding.await *> queuedRequests("nested-request", 1)
          closingResult <- scope.close(Exit.unit).exit
          nestedResult <- nested.await
        } yield {
          assertTrue(closingResult == Exit.unit) &&
          assertTrue(nestedResult.isInterruptedOnly)
        }
      },
      test("shutdown logs an idle close failure and continues closing connections") {
        val closeAttempts = new AtomicInteger(0)
        val connections = (0 until 3).map { i =>
          new StubConnection(_ => true) {
            override def close(): Unit = {
              val _ = closeAttempts.incrementAndGet()
              if (i == 0) throw new SQLException("close failed")
              super.close()
            }
          }
        }
        for {
          remaining <- Ref.make(connections)
          create = remaining.modify(conns => (conns.head, conns.tail))
          scope <- Scope.make
          pool <- scope.extend[Any](ConnectionPool.create(create, config.copy(size = connections.size)))
          _ <- ZIO.scoped[Any](ZIO.foreachDiscard(connections)(_ => pool.get))
          result <- scope.close(Exit.unit).exit
          logged <- ZTestLogger.logOutput
        } yield {
          val closeFailureLogged = logged.exists { entry =>
            entry.message() == "Could not close connection" &&
            entry.cause.failureOption.exists { error =>
              error.isInstanceOf[SQLException] && error.asInstanceOf[SQLException].getMessage == "close failed"
            }
          }
          assertTrue(result.isSuccess) &&
          assertTrue(closeAttempts.get == connections.size) &&
          assertTrue(connections.tail.forall(_.isClosed)) &&
          assertTrue(closeFailureLogged)
        }
      },
    ),
    test("requests wait for a connection until the queue is full") {
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

        _ <- ZIO.scoped[Any](ZIO.foreachDiscard((1 to config.size).toList)(_ => pool.get))
        createdInitial <- createdRef.get

        _ <- TestClock.adjust(config.maxConnectionLifetime)
        _ <- TestClock.adjust(1.second)
        validAfterAllExceedMaxLifetime <- ZIO.scoped[Any] {
          for {
            c <- pool.get
            now <- secondTime
          } yield c.isValid(now)
        }

        _ <- ZIO.scoped[Any](ZIO.foreachDiscard((1 to config.size).toList)(_ => pool.get))
        createdRefreshed <- createdRef.get
        // expired connections are closed without the request waiting
        _ <- eventually(createdInitial.forall(_.isClosed))
      } yield {
        assertTrue(createdInitial.size == 5) &&
        assertTrue(createdInitial.forall(_.isClosed)) &&
        assertTrue(validAfterAllExceedMaxLifetime) &&
        assertTrue(createdRefreshed.size == 10)
      }
    },
    test("checkout skips expired idle connections before attempting creation") {
      for {
        attempts <- Ref.make(0)
        older = new StubConnection(_ => true)
        younger = new StubConnection(_ => true)
        error = DatabaseError.Connection.Transient(new SQLTransientConnectionException)
        create = attempts.updateAndGet(_ + 1).flatMap {
          case 1 => ZIO.succeed(older)
          case 2 => ZIO.succeed(younger)
          case _ => ZIO.fail(error)
        }
        pool <- ConnectionPool.create(create, config.copy(size = 2))
        olderScope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        youngerScope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        _ <- olderScope.extend[Any](pool.get)
        _ <- TestClock.adjust(30.seconds)
        _ <- youngerScope.extend[Any](pool.get)
        // Return the older connection last so it is on top of the idle stack.
        _ <- youngerScope.close(Exit.unit)
        _ <- olderScope.close(Exit.unit)
        _ <- TestClock.adjust(31.seconds)
        next <- ZIO.scoped[Any](pool.get.map(_.unwrap(classOf[StubConnection]))).either
        attempts <- attempts.get
        _ <- eventually(older.isClosed)
      } yield {
        assertTrue(next == Right(younger)) &&
        assertTrue(attempts == 2) &&
        assertTrue(!younger.isClosed)
      }
    },
    test("the most recently returned connection is reused first") {
      for {
        tuple <- recordingPool(config.copy(size = 2))(_ => new StubConnection(_ => true))
        (pool, created) = tuple
        first <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        second <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        _ <- first.extend[Any](pool.get)
        _ <- second.extend[Any](pool.get)
        _ <- first.close(Exit.unit)
        _ <- second.close(Exit.unit)
        reused <- ZIO.scoped[Any](pool.get.map(_.unwrap(classOf[StubConnection])))
        created <- created
      } yield {
        assertTrue(created.size == 2) &&
        assertTrue(created.lastOption.contains(reused))
      }
    },
    test("a connection expires when the clock's nanoTime wraps around during its lifetime") {
      val nanos = new AtomicLong(Long.MaxValue - 1.second.toNanos)
      ZIO.clock.flatMap { base =>
        ZIO.withClock(nanoTimeClock(base, nanos))(for {
          tuple <- recordingPool()(_ => new StubConnection(_ => true))
          (pool, created) = tuple
          _ <- ZIO.scoped[Any](pool.get)
          beforeLifetime <- created.map(_.map(_.isClosed))
          // past the lifetime and past Long.MaxValue, so nanoTime is now negative
          _ <- ZIO.succeed(nanos.addAndGet(config.maxConnectionLifetime.toNanos))
          _ <- ZIO.scoped[Any](pool.get)
          created <- awaitClosed(created, 1)
        } yield {
          assertTrue(nanos.get < 0) &&
          assertTrue(beforeLifetime == Chunk(false)) &&
          assertTrue(created.map(_.isClosed) == Chunk(true, false))
        })
      }
    },
    suite("connection check-in")(
      test("alwaysInvalidateOnFailure controls invalidation after a request failure") {
        def run(alwaysInvalidateOnFailure: Boolean) = for {
          tuple <- recordingPool(config.copy(alwaysInvalidateOnFailure = alwaysInvalidateOnFailure)) { _ =>
            new StubConnection(_ => true)
          }
          (pool, created) = tuple
          result <- ZIO.scoped[Any] {
            pool.get *> ZIO.fail(new RuntimeException("boom"))
          }.either
          created <- awaitClosed(created, if (alwaysInvalidateOnFailure) 1 else 0)
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
      test("connection is invalidated if state cannot be reset") {
        def run(closeEarly: Boolean) = for {
          tuple <- recordingPool() { i =>
            if (i == 0) StubConnection.failingReset() else new StubConnection(_ => true)
          }
          (pool, created) = tuple
          closed <- ZIO.scoped[Any](pool.get.flatMap(c => ZIO.attempt(c.close()).either.when(closeEarly)))
          _ <- ZIO.scoped[Any](pool.get)
          created <- awaitClosed(created, 1)
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
              override def abort(executor: Executor): Unit = ()
            }
          }
          (pool, created) = tuple
          _ <- ZIO.scoped[Any](pool.get.map(_.abort((r: Runnable) => r.run())))
          _ <- ZIO.scoped[Any](pool.get)
          created <- awaitClosed(created, 1)
        } yield {
          assertTrue(created.map(_.isClosed) == Chunk(true, false))
        }
      },
      test("connection cannot be used after it is returned to the pool") {
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
      test("a connection that times out while being reset is replaced") {
        for {
          tuple <- recordingPool(config.copy(size = 1, validationTimeout = 200.millis)) { i =>
            lazy val dead: StubConnection = new StubConnection(_ => true) {
              // a dead connection only fails a call once the network timeout passes. without the
              // timeout this returns, so the connection would be reused and the test fails
              override def clearWarnings(): Unit = {
                if (dead.getNetworkTimeout == 200) throw new SQLException("timed out")
              }
            }
            if (i == 0) dead else new StubConnection(_ => true)
          }
          (pool, created) = tuple
          returned <- ZIO.scoped[Any](pool.get).exit
          next <- ZIO.scoped[Any](pool.get).exit
          created <- awaitClosed(created, 1)
        } yield {
          assertTrue(returned.isSuccess) &&
          assertTrue(next.isSuccess) &&
          assertTrue(created.map(_.isClosed) == Chunk(true, false))
        }
      },
      test("after a request failure the connection is validated once it has been reset") {
        def run(valid: Boolean) = for {
          rolledBackBeforeValidation <- ZIO.succeed(new AtomicReference(Option.empty[Boolean]))
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
          created <- awaitClosed(created, if (valid) 0 else 1)
        } yield (order, created.map(_.isClosed))

        for {
          whenValid <- run(valid = true)
          whenInvalid <- run(valid = false)
        } yield {
          assertTrue(whenValid == ((Some(true), Chunk(false)))) &&
          assertTrue(whenInvalid == ((Some(true), Chunk(true, false))))
        }
      },
      test("a successful request discards a connection past its lifetime") {
        for {
          tuple <- recordingPool()(_ => new StubConnection(_ => true))
          (pool, created) = tuple
          _ <- ZIO.scoped[Any] {
            pool.get *> TestClock.adjust(config.maxConnectionLifetime.plusSeconds(1))
          }
          _ <- ZIO.scoped[Any](pool.get)
          created <- awaitClosed(created, 1)
        } yield {
          assertTrue(created.map(_.isClosed) == Chunk(true, false))
        }
      },
      test("a borrower defect is propagated after the connection is checked in") {
        val defect = new RuntimeException("boom")
        for {
          tuple <- recordingPool()(_ => new StubConnection(_ => true))
          (pool, created) = tuple
          failed <- ZIO.scoped[Any](pool.get *> ZIO.die(defect)).exit
          next <- ZIO.scoped[Any](pool.get).exit
          created <- created
        } yield {
          assertTrue(failed.causeOption.flatMap(_.dieOption).contains(defect)) &&
          assertTrue(next.isSuccess) &&
          assertTrue(created.map(_.isClosed) == Chunk(false))
        }
      },
    ),
    suite("boundary values")(
      test("durations are rounded up and capped when converted to JDBC timeouts") {
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
        val during = new AtomicInteger(-1)
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
      test("an Int.MaxValue queue size does not overflow pool capacity") {
        for {
          _ <- ZIO.unit
          create = ZIO.succeed(new StubConnection(_ => true))
          pool <- ConnectionPool.create(create, config.copy(size = 1, queueSize = Int.MaxValue))
          acquired <- ZIO.scoped[Any](pool.get).exit
        } yield {
          assertTrue(acquired.isSuccess)
        }
      },
      test("a connection past its lifetime is discarded after a failure without being validated") {
        val validations = new AtomicInteger(0)
        for {
          tuple <- recordingPool() { _ =>
            new StubConnection(_ => {
              val _ = validations.incrementAndGet()
              true
            })
          }
          (pool, created) = tuple
          // a failure validates a connection that is going back into the pool, one past its
          // lifetime is not going back
          _ <- ZIO.scoped[Any] {
            pool.get *> TestClock.adjust(config.maxConnectionLifetime.plusSeconds(1)) *> ZIO.fail(new RuntimeException("boom"))
          }.either
          created <- awaitClosed(created, 1)
        } yield {
          assertTrue(validations.get == 0) &&
          assertTrue(created.map(_.isClosed) == Chunk(true))
        }
      },
    ),
    test("a request made in an uninterruptible region can wait for a connection") {
      for {
        pool <- ConnectionPool.create(
          ZIO.succeed(new StubConnection(_ => true)),
          config.copy(name = "uninterruptible-wait", size = 1),
        )
        holding <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        holder <- ZIO.scoped[Any](pool.get *> holding.succeed(()) *> release.await).fork
        _ <- holding.await
        waiter <- ZIO.scoped[Any](pool.get).uninterruptible.fork
        _ <- queuedRequests("uninterruptible-wait", 1)
        _ <- release.succeed(())
        _ <- holder.join
        result <- waiter.await
      } yield {
        assertTrue(result.isSuccess)
      }
    },
    suite("queueing")(
      test("a queue size of zero rejects requests only when every connection is in use") {
        for {
          _ <- ZIO.unit
          create = ZIO.succeed(new StubConnection(_ => true))
          pool <- ConnectionPool.create(create, config.copy(size = 1, queueSize = 0))
          acquired <- pool.get.exit
          rejected <- ZIO.scoped[Any](pool.get).exit
        } yield {
          assertTrue(acquired.isSuccess) &&
          assertTrue(rejected == Exit.fail(DatabaseError.Connection.Rejected(0)))
        }
      },
      test("a full queue only rejects requests that would have to wait") {
        for {
          _ <- ZIO.unit
          create = ZIO.succeed(new StubConnection(_ => true))
          pool <- ConnectionPool.create(create, config.copy(name = "full-queue", size = 2, queueSize = 1))
          _ <- pool.get
          _ <- pool.get
          rejected <- requestWithQueueFull(pool, "full-queue")
        } yield {
          assertTrue(rejected == Exit.fail(DatabaseError.Connection.Rejected(1)))
        }
      },
      test("a request can get a connection after another was rejected") {
        for {
          _ <- ZIO.unit
          create = ZIO.succeed(new StubConnection(_ => true))
          pool <- ConnectionPool.create(create, config.copy(name = "after-rejection", size = 1, queueSize = 1))
          _ <- ZIO.scoped[Any](pool.get)
          _ <- ZIO.scoped[Any](pool.get)
          held <- Promise.make[Nothing, Unit]
          done <- Promise.make[Nothing, Unit]
          holder <- ZIO.scoped[Any](pool.get *> held.succeed(()) *> done.await).fork
          _ <- held.await
          rejected <- requestWithQueueFull(pool, "after-rejection")
          _ <- done.succeed(())
          _ <- holder.join
          acquired <- ZIO.scoped[Any](pool.get).exit
        } yield {
          assertTrue(rejected == Exit.fail(DatabaseError.Connection.Rejected(1))) &&
          assertTrue(acquired.isSuccess)
        }
      },
    ),
    test("a newly created connection is not discarded before its first checkout") {
      val clock = steppingClock(2L)
      for {
        createdRef <- Ref.make(0)
        create = createdRef.update(_ + 1).as(new StubConnection(_ => true))
        pool <- ConnectionPool.create(
          create,
          config.copy(size = 1, maxConnectionLifetime = Duration.ofNanos(1)),
        )
        result <- ZIO.withClock(clock)(ZIO.scoped[Any](pool.get).exit)
        created <- createdRef.get
      } yield {
        assertTrue(result.isSuccess) &&
        assertTrue(created == 1)
      }
    },
    suite("check-in concurrency")(
      test("a connection occupies pool capacity until check-in completes") {
        val validating = new CountDownLatch(1)
        val proceed = new CountDownLatch(1)
        val create = ZIO.succeed(new StubConnection(_ => {
          validating.countDown()
          val _ = proceed.await(10, TimeUnit.SECONDS)
          true
        }))
        for {
          pool <- ConnectionPool.create(create, config.copy(name = "check-in-capacity", size = 1, queueSize = 1))
          // a failure validates the connection before it is returned to the pool
          holder <- ZIO.scoped[Any](pool.get *> ZIO.fail(new RuntimeException("boom"))).exit.fork
          validatingStarted <- ZIO.attemptBlocking(validating.await(10, TimeUnit.SECONDS))
          rejected <- requestWithQueueFull(pool, "check-in-capacity")
          _ <- ZIO.succeed(proceed.countDown())
          _ <- holder.join
          acquired <- ZIO.scoped[Any](pool.get).exit
        } yield {
          assertTrue(validatingStarted) &&
          assertTrue(rejected == Exit.fail(DatabaseError.Connection.Rejected(1))) &&
          assertTrue(acquired.isSuccess)
        }
      },
      test("a connection is reset before it is returned when finalizers run in parallel") {
        val resetting = new CountDownLatch(1)
        val proceed = new CountDownLatch(1)
        val create = ZIO.succeed(new StubConnection(_ => true) {
          override def clearWarnings(): Unit = {
            resetting.countDown()
            val _ = proceed.await(10, TimeUnit.SECONDS)
          }
        })
        for {
          pool <- ConnectionPool.create(create, config.copy(name = "parallel-finalizers", size = 1, queueSize = 1))
          holder <- ZIO.scoped[Any](ZIO.parallelFinalizers(pool.get)).fork
          resettingStarted <- ZIO.attemptBlocking(resetting.await(10, TimeUnit.SECONDS))
          // can only get the connection once the reset has finished
          next <- ZIO.scoped[Any](pool.get).fork
          _ <- queuedRequests("parallel-finalizers", 1)
          early <- next.poll
          _ <- ZIO.succeed(proceed.countDown())
          _ <- holder.join
          _ <- next.join
        } yield {
          assertTrue(resettingStarted) &&
          assertTrue(early.isEmpty)
        }
      },
    ),
    suite("interruption")(
      test("a connection invalidated by an interrupted request is replaced for the next request") {
        for {
          createdRef <- Ref.make(0)
          create = createdRef.update(_ + 1).as(new StubConnection(_ => true))
          pool <- ConnectionPool.create(create, config.copy(size = 1, alwaysInvalidateOnFailure = true))
          held <- Promise.make[Nothing, Unit]
          holder <- ZIO.scoped[Any](pool.get *> held.succeed(()) *> ZIO.never).fork
          _ <- held.await
          _ <- holder.interrupt
          next <- ZIO.scoped[Any](pool.get).exit
          created <- createdRef.get
        } yield {
          assertTrue(next.isSuccess) &&
          assertTrue(created == 2)
        }
      },
      test("a request interrupted while waiting does not interrupt the next request") {
        for {
          _ <- ZIO.unit
          create = ZIO.succeed(new StubConnection(_ => true))
          pool <- ConnectionPool.create(create, config.copy(name = "interrupted-waiter", size = 1, alwaysInvalidateOnFailure = true))
          held <- Promise.make[Nothing, Unit]
          finish <- Promise.make[Nothing, Unit]
          holder <- ZIO.scoped[Any](pool.get *> held.succeed(()) *> finish.await *> ZIO.fail(new RuntimeException("boom"))).exit.fork
          _ <- held.await
          waiter <- ZIO.scoped[Any](pool.get).fork
          _ <- queuedRequests("interrupted-waiter", 1)
          _ <- waiter.interrupt
          _ <- finish.succeed(())
          _ <- holder.join
          next <- ZIO.scoped[Any](pool.get).exit
        } yield {
          assertTrue(next.isSuccess)
        }
      },
      test("interrupted requests do not leak connections or demand") {
        for {
          _ <- ZIO.unit
          create = ZIO.succeed(new StubConnection(_ => true))
          pool <- ConnectionPool.create(create, config.copy(size = 1))
          _ <- ZIO.foreachDiscard(1 to 500) { _ =>
            ZIO.scoped[Any](pool.get *> ZIO.never).fork.flatMap(f => ZIO.yieldNow *> f.interrupt)
          }
          acquired <- ZIO.scoped[Any](pool.get).exit
        } yield {
          assertTrue(acquired.isSuccess)
        }
      },
      test("interrupting an active request returns a valid connection to the pool") {
        val validations = new AtomicInteger(0)
        for {
          tuple <- recordingPool() { _ =>
            new StubConnection(_ => {
              val _ = validations.incrementAndGet()
              true
            })
          }
          (pool, created) = tuple
          acquired <- Promise.make[Nothing, Unit]
          holder <- ZIO.scoped[Any](pool.get *> acquired.succeed(()) *> ZIO.never).fork
          _ <- acquired.await
          interrupted <- holder.interrupt
          next <- ZIO.scoped[Any](pool.get).exit
          created <- created
        } yield {
          assertTrue(interrupted.isInterruptedOnly) &&
          assertTrue(next.isSuccess) &&
          assertTrue(validations.get == 1) &&
          assertTrue(created.map(_.isClosed) == Chunk(false))
        }
      },
    ),
    test("connections are only created for a request that needs one") {
      for {
        createdRef <- Ref.make(0)
        create = createdRef.update(_ + 1).as(new StubConnection(_ => true))
        pool <- ConnectionPool.create(create, config)
        createdUpFront <- createdRef.get
        _ <- ZIO.scoped[Any](pool.get)
        _ <- ZIO.scoped[Any](pool.get)
        created <- createdRef.get
      } yield {
        assertTrue(createdUpFront == 0) &&
        assertTrue(created == 1)
      }
    },
    test("validation and close failures during check-in are logged") {
      for {
        tuple <- recordingPool() { _ =>
          new StubConnection(_ => true) {
            override def isValid(timeout: Int): Boolean = throw new SQLException("validation failed")
            override def close(): Unit = throw new SQLException("close failed")
          }
        }
        (pool, _) = tuple
        // a failure validates the connection, which fails, so it is discarded and closing it fails
        _ <- ZIO.scoped[Any](pool.get *> ZIO.fail(new RuntimeException("boom"))).either
        // the close runs in the background
        logged <- (ZIO.yieldNow *> ZTestLogger.logOutput).repeatUntil(_.exists(_.message() == "Could not close connection"))
      } yield {
        def failedWith(cause: zio.Cause[Any], message: String) = cause.failureOption.exists { error =>
          error.isInstanceOf[Throwable] && error.asInstanceOf[Throwable].getMessage == message
        }
        val warnings = logged.filter(_.logLevel == LogLevel.Warning)
        assertTrue(warnings.exists(e => e.message() == "Could not validate connection" && failedWith(e.cause, "validation failed"))) &&
        assertTrue(warnings.exists(e => e.message() == "Could not close connection" && failedWith(e.cause, "close failed")))
      }
    },
    suite("connection creation interruption")(
      test("requests waiting for connection creation are counted until checkout completes") {
        val name = "waiting-for-connection-creation"
        for {
          connecting <- Promise.make[Nothing, Unit]
          opened <- Promise.make[Nothing, Unit]
          acquired <- Promise.make[Nothing, Unit]
          finish <- Promise.make[Nothing, Unit]
          create = connecting.succeed(()) *> opened.await.as(new StubConnection(_ => true))
          pool <- ConnectionPool.create(create, config.copy(name = name, size = 1))
          request <- ZIO.scoped[Any](pool.get *> acquired.succeed(()) *> finish.await).fork
          _ <- connecting.await
          waitingDuringCreation <- ConnectionPool.metricWaiting(name).value
          inUseDuringCreation <- ConnectionPool.metricInUse(name).value
          _ <- opened.succeed(()) *> acquired.await
          waitingAfterCheckout <- ConnectionPool.metricWaiting(name).value
          inUseAfterCheckout <- ConnectionPool.metricInUse(name).value
          _ <- finish.succeed(()) *> request.join
          waitingAfterReturn <- ConnectionPool.metricWaiting(name).value
          inUseAfterReturn <- ConnectionPool.metricInUse(name).value
        } yield {
          assertTrue(waitingDuringCreation.value == 1.0, inUseDuringCreation.value == 0.0) &&
          assertTrue(waitingAfterCheckout.value == 0.0, inUseAfterCheckout.value == 1.0) &&
          assertTrue(waitingAfterReturn.value == 0.0, inUseAfterReturn.value == 0.0)
        }
      },
      test("an interrupted request leaves connection creation running for the next request") {
        for {
          connecting <- Promise.make[Nothing, Unit]
          opened <- Promise.make[Nothing, Unit]
          createdRef <- Ref.make(0)
          create = connecting.succeed(()) *> opened.await *> createdRef.update(_ + 1).as(new StubConnection(_ => true))
          pool <- ConnectionPool.create(create, config.copy(name = "abandoned-creation", size = 1, queueSize = 1))
          request <- ZIO.scoped[Any](pool.get).fork
          _ <- connecting.await
          // does not wait for the connection to be created
          gaveUp <- request.interrupt
          // the attempt still holds the only connection
          rejected <- requestWithQueueFull(pool, "abandoned-creation")
          _ <- opened.succeed(())
          next <- ZIO.scoped[Any](pool.get).exit
          created <- createdRef.get
        } yield {
          assertTrue(gaveUp.isInterruptedOnly) &&
          assertTrue(rejected == Exit.fail(DatabaseError.Connection.Rejected(1))) &&
          assertTrue(
            DatabaseError.Connection.Rejected(1).getMessage ==
              "No connection available, all connections are in use or being created and the queue of 1 waiting requests is full",
          ) &&
          assertTrue(next.isSuccess) &&
          assertTrue(created == 1)
        }
      },
      test("a connection creation is counted as creating until it finishes, also once abandoned") {
        val name = "abandoned-creation-creating"
        for {
          connecting <- Promise.make[Nothing, Unit]
          opened <- Promise.make[Nothing, Unit]
          create = connecting.succeed(()) *> opened.await.as(new StubConnection(_ => true))
          pool <- ConnectionPool.create(create, config.copy(name = name, size = 1))
          request <- ZIO.scoped[Any](pool.get).fork
          _ <- connecting.await
          creatingWaited <- ConnectionPool.metricCreating(name).value
          _ <- request.interrupt
          waitingAbandoned <- ConnectionPool.metricWaiting(name).value
          inUseAbandoned <- ConnectionPool.metricInUse(name).value
          creatingAbandoned <- ConnectionPool.metricCreating(name).value
          _ <- opened.succeed(())
          next <- ZIO.scoped[Any](pool.get).exit
          creatingSettled <- ConnectionPool.metricCreating(name).value
        } yield {
          assertTrue(creatingWaited.value == 1.0) &&
          assertTrue(waitingAbandoned.value == 0.0, inUseAbandoned.value == 0.0, creatingAbandoned.value == 1.0) &&
          assertTrue(next.isSuccess, creatingSettled.value == 0.0)
        }
      },
      test("a timeout while creating a connection is returned to the request") {
        val timedOut = DatabaseError.Connection.Transient(new SQLTransientConnectionException("timed out"))
        for {
          _ <- ZIO.unit
          create = ZIO.never.interruptible.timeoutFail(timedOut)(1.second)
          pool <- ConnectionPool.create(create, config.copy(size = 1))
          // the timeout can only fire if it can interrupt the attempt
          timeOut = ZIO.scoped[Any](pool.get).fork.flatMap { request =>
            sleepingFor(1.second) *> TestClock.adjust(1.second) *> request.await
          }
          result <- timeOut
          // the attempt finished, so its slot is free for the next request, which does not wait
          next <- timeOut
        } yield {
          assertTrue(result == Exit.fail(timedOut)) &&
          assertTrue(next == Exit.fail(timedOut))
        }
      },
      test("an interruption from connection creation releases its slot before the request returns") {
        for {
          attempts <- Ref.make(0)
          create = attempts.updateAndGet(_ + 1).flatMap { attempt =>
            if (attempt == 1) ZIO.interrupt else ZIO.succeed(new StubConnection(_ => true))
          }
          pool <- ConnectionPool.create(create, config.copy(size = 1, queueSize = 0))
          first <- ZIO.scoped[Any](pool.get).exit
          // with no queue, this is rejected unless the slot is already free
          next <- ZIO.scoped[Any](pool.get).exit
          logged <- ZTestLogger.logOutput
        } yield {
          assertTrue(first.isInterruptedOnly) &&
          assertTrue(next.isSuccess) &&
          assertTrue(!logged.exists(_.message() == "Could not create connection"))
        }
      },
      test("a creation failure that carries an interruption is returned to the request without being logged") {
        val error = DatabaseError.Connection.Transient(new SQLTransientConnectionException("failed"))
        val cause = zio.Cause.fail(error) ++ zio.Cause.interrupt(zio.FiberId.None)
        for {
          attempts <- Ref.make(0)
          create = attempts.updateAndGet(_ + 1).flatMap { attempt =>
            if (attempt == 1) ZIO.failCause(cause) else ZIO.succeed(new StubConnection(_ => true))
          }
          pool <- ConnectionPool.create(create, config.copy(size = 1, queueSize = 0))
          first <- ZIO.scoped[Any](pool.get).exit
          // with no queue, this is rejected unless the slot is already free
          next <- ZIO.scoped[Any](pool.get).exit
          logged <- ZTestLogger.logOutput
        } yield {
          assertTrue(first.causeOption.exists(c => c.failureOption.contains(error) && c.isInterrupted)) &&
          assertTrue(next.isSuccess) &&
          assertTrue(!logged.exists(_.message() == "Could not create connection"))
        }
      },
      test("a failure or defect after interruption is logged and releases pool capacity") {
        def run(
          first: ZIO[Any, DatabaseError.Connection, Connection],
        ): ZIO[
          Scope,
          Nothing,
          (Exit[DatabaseError.Connection, Connection], Exit[DatabaseError.Connection, Connection], Int),
        ] = for {
          connecting <- Promise.make[Nothing, Unit]
          proceed <- Promise.make[Nothing, Unit]
          attempts <- Ref.make(0)
          create = attempts.updateAndGet(_ + 1).flatMap { attempt =>
            if (attempt == 1) connecting.succeed(()) *> proceed.await *> first
            else ZIO.succeed(new StubConnection(_ => true))
          }
          pool <- ConnectionPool.create(create, config.copy(size = 1))
          request <- ZIO.scoped[Any](pool.get).fork
          _ <- connecting.await
          interrupted <- request.interrupt
          _ <- proceed.succeed(())
          next <- ZIO.scoped[Any](pool.get).exit
          attempts <- attempts.get
        } yield (interrupted, next, attempts)

        val error = DatabaseError.Connection.Transient(new SQLTransientConnectionException("failed"))
        val defect = new RuntimeException("boom")
        for {
          failed <- run(ZIO.fail(error))
          (failedInterruption, failedNext, failedAttempts) = failed
          defective <- run(ZIO.die(defect))
          (defectInterruption, defectNext, defectAttempts) = defective
          logged <- ZTestLogger.logOutput
        } yield {
          val warnings = logged.filter(_.message() == "Could not create connection")
          assertTrue(failedInterruption.isInterruptedOnly, defectInterruption.isInterruptedOnly) &&
          assertTrue(failedNext.isSuccess, defectNext.isSuccess) &&
          assertTrue(failedAttempts == 2, defectAttempts == 2) &&
          assertTrue(warnings.exists(_.cause.failureOption.contains(error))) &&
          assertTrue(warnings.exists(_.cause.dieOption.contains(defect)))
        }
      },
      test("a failure or defect published just before interruption is logged and releases pool capacity") {
        def run(first: ZIO[Any, DatabaseError.Connection, Connection]) = for {
          tasks <- ZIO.succeed(new ConcurrentLinkedQueue[Runnable])
          executor = zio.Executor.fromJavaExecutor(task => {
            val _ = tasks.add(task)
          })
          connecting <- Promise.make[Nothing, Unit]
          proceed <- Promise.make[Nothing, Unit]
          attempts <- Ref.make(0)
          create = attempts.updateAndGet(_ + 1).flatMap { attempt =>
            if (attempt == 1) connecting.succeed(()) *> proceed.await *> first
            else ZIO.succeed(new StubConnection(_ => true))
          }
          pool <- ConnectionPool.create(create, config.copy(size = 1))
          request <- ZIO.scoped[Any](pool.get).onExecutor(executor).fork
          // Run the request and creator fibers until both are suspended on their promises.
          _ <- (ZIO.yieldNow *> ZIO.succeed(Option(tasks.poll()).foreach(_.run())))
            .repeatUntilZIO(_ => connecting.isDone.map(_ && tasks.isEmpty))
          _ <- proceed.succeed(())
          // Only the creator is runnable. Let it publish its failure while the
          // request's continuation stays queued, then interrupt the request.
          _ <- ZIO.attempt(tasks.remove().run())
          _ <- request.interruptFork
          // Also run the fiber settling the attempt, as nothing else runs this executor.
          _ <- (ZIO.yieldNow *> ZIO.succeed(Option(tasks.poll()).foreach(_.run())))
            .repeatUntilZIO(_ => request.poll.map(_.nonEmpty && tasks.isEmpty))
          interrupted <- request.await
          next <- ZIO.scoped[Any](pool.get).exit
          attempts <- attempts.get
        } yield (interrupted, next, attempts)

        val error = DatabaseError.Connection.Transient(new SQLTransientConnectionException("late failure"))
        val defect = new RuntimeException("late defect")
        for {
          failed <- run(ZIO.fail(error))
          (failedInterruption, failedNext, failedAttempts) = failed
          defective <- run(ZIO.die(defect))
          (defectInterruption, defectNext, defectAttempts) = defective
          logged <- ZTestLogger.logOutput
        } yield {
          val warnings = logged.filter(e => e.logLevel == LogLevel.Warning && e.message() == "Could not create connection")
          assertTrue(failedInterruption.isInterruptedOnly, defectInterruption.isInterruptedOnly) &&
          assertTrue(failedNext.isSuccess, defectNext.isSuccess) &&
          assertTrue(failedAttempts == 2, defectAttempts == 2) &&
          assertTrue(warnings.count(_.cause.failureOption.contains(error)) == 1) &&
          assertTrue(warnings.count(_.cause.dieOption.contains(defect)) == 1)
        }
      },
    ),
    test("neither checkout nor shutdown waits for an expired connection to close") {
      val closing = new CountDownLatch(1)
      val proceed = new CountDownLatch(1)
      // The lifetime ends by moving nanoTime, adjusting the test clock would
      // wait for the blocked close.
      val nanos = new AtomicLong(0L)
      ZIO.clock.flatMap { base =>
        ZIO.withClock(nanoTimeClock(base, nanos))(for {
          scope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
          _ <- ZIO.addFinalizer(ZIO.succeed(proceed.countDown()))
          tuple <- scope.extend[Any](recordingPool() { i =>
            if (i == 0) blockingClose(closing, proceed) else new StubConnection(_ => true)
          })
          (pool, created) = tuple
          _ <- ZIO.scoped[Any](pool.get)
          _ <- ZIO.succeed(nanos.addAndGet(config.maxConnectionLifetime.toNanos))
          // checkout discards the expired connection, the close holds no slot,
          // so a connection is created in the only slot while it runs
          next <- ZIO.scoped[Any](pool.get).exit
          closeStarted <- ZIO.attemptBlocking(closing.await(10, TimeUnit.SECONDS))
          // Use live time because the JDBC close is blocked on a real thread.
          shutdown <- Live.live(scope.close(Exit.unit).timeout(5.seconds))
          created <- created
        } yield {
          assertTrue(next.isSuccess) &&
          assertTrue(closeStarted) &&
          assertTrue(shutdown.isDefined) &&
          assertTrue(created.size == 2) &&
          // the expired connection is still closing, shutdown closed the idle one
          assertTrue(created.map(_.isClosed) == Chunk(false, true))
        })
      }
    },
    test("neither check-in nor shutdown waits for an invalidated connection to close") {
      val closing = new CountDownLatch(1)
      val proceed = new CountDownLatch(1)
      for {
        scope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        _ <- ZIO.addFinalizer(ZIO.succeed(proceed.countDown()))
        tuple <- scope.extend[Any](recordingPool(config.copy(size = 1, alwaysInvalidateOnFailure = true)) { i =>
          if (i == 0) blockingClose(closing, proceed) else new StubConnection(_ => true)
        })
        (pool, created) = tuple
        // the failure invalidates the connection, check-in returns while it closes
        failed <- ZIO.scoped[Any](pool.get *> ZIO.fail("boom")).exit
        closeStarted <- ZIO.attemptBlocking(closing.await(10, TimeUnit.SECONDS))
        // the close holds no slot, so the only slot is free for the next request
        next <- ZIO.scoped[Any](pool.get).exit
        // Use live time because the JDBC close is blocked on a real thread.
        shutdown <- Live.live(scope.close(Exit.unit).timeout(5.seconds))
        created <- created
      } yield {
        assertTrue(failed == Exit.fail("boom")) &&
        assertTrue(closeStarted) &&
        assertTrue(next.isSuccess) &&
        assertTrue(shutdown.isDefined) &&
        assertTrue(created.size == 2) &&
        // the invalidated connection is still closing, shutdown closed the idle one
        assertTrue(created.map(_.isClosed) == Chunk(false, true))
      }
    },
    suite("idle expiry")(
      test("an idle connection is closed when its lifetime ends") {
        for {
          tuple <- recordingPool()(_ => new StubConnection(_ => true))
          (pool, created) = tuple
          _ <- ZIO.scoped[Any](pool.get)
          // every lifetime is at least 90% of the maximum
          _ <- TestClock.adjust(config.maxConnectionLifetime.multipliedBy(8).dividedBy(10))
          beforeLifetime <- created.map(_.map(_.isClosed))
          _ <- TestClock.adjust(config.maxConnectionLifetime)
          afterLifetime <- awaitClosed(created, 1).map(_.map(_.isClosed))
          next <- ZIO.scoped[Any](pool.get).exit
          created <- created
        } yield {
          assertTrue(beforeLifetime == Chunk(false)) &&
          assertTrue(afterLifetime == Chunk(true)) &&
          assertTrue(next.isSuccess) &&
          assertTrue(created.map(_.isClosed) == Chunk(true, false))
        }
      },
      test("an expired idle connection is closed by the next sweep, 30 seconds after the last") {
        val nanos = new AtomicLong(0L)
        ZIO.clock.flatMap { base =>
          ZIO.withClock(nanoTimeClock(base, nanos))(for {
            tuple <- recordingPool()(_ => new StubConnection(_ => true))
            (pool, created) = tuple
            _ <- ZIO.scoped[Any](pool.get)
            // the first sweep has run, the sweeper sleeps until the next
            _ <- sleepingFor(30.seconds)
            _ <- ZIO.succeed(nanos.addAndGet(config.maxConnectionLifetime.toNanos))
            _ <- TestClock.adjust(29.seconds)
            beforeSweep <- created.map(_.map(_.isClosed))
            _ <- TestClock.adjust(1.second)
            afterSweep <- awaitClosed(created, 1).map(_.map(_.isClosed))
          } yield {
            assertTrue(beforeSweep == Chunk(false)) &&
            assertTrue(afterSweep == Chunk(true))
          })
        }
      },
      test("a connection in use when its lifetime ends is closed at check-in") {
        for {
          tuple <- recordingPool()(_ => new StubConnection(_ => true))
          (pool, created) = tuple
          inUse <- ZIO.scoped[Any] {
            pool.get *> TestClock.adjust(config.maxConnectionLifetime) *> created.map(_.map(_.isClosed))
          }
          created <- awaitClosed(created, 1)
        } yield {
          assertTrue(inUse == Chunk(false)) &&
          assertTrue(created.map(_.isClosed) == Chunk(true))
        }
      },
      test("a connection whose lifetime ends during check-in is closed once idle") {
        val validating = new CountDownLatch(1)
        val proceed = new CountDownLatch(1)
        // The lifetime ends by moving nanoTime, adjusting the test clock would
        // wait for the blocked validation.
        val nanos = new AtomicLong(0L)
        ZIO.clock.flatMap { base =>
          ZIO.withClock(nanoTimeClock(base, nanos))(for {
            _ <- ZIO.addFinalizer(ZIO.succeed(proceed.countDown()))
            tuple <- recordingPool() { _ =>
              new StubConnection(_ => {
                validating.countDown()
                proceed.await(10, TimeUnit.SECONDS)
              })
            }
            (pool, created) = tuple
            // the failure makes check-in validate the connection, which blocks
            request <- ZIO.scoped[Any](pool.get *> ZIO.fail("boom")).exit.fork
            validated <- ZIO.attemptBlocking(validating.await(10, TimeUnit.SECONDS))
            // the age check has passed, the lifetime ends while validating
            _ <- ZIO.succeed(nanos.addAndGet(config.maxConnectionLifetime.toNanos))
            _ <- ZIO.succeed(proceed.countDown())
            failed <- request.join
            idle <- created.map(_.map(_.isClosed))
            // the sweeper closes it the next time it runs
            _ <- TestClock.adjust(30.seconds)
            created <- awaitClosed(created, 1)
          } yield {
            assertTrue(validated) &&
            assertTrue(failed == Exit.fail("boom")) &&
            assertTrue(idle == Chunk(false)) &&
            assertTrue(created.map(_.isClosed) == Chunk(true))
          })
        }
      },
    ),
    test("a network timeout is set on a connection before it is closed") {
      val during = new AtomicInteger(-1)
      lazy val connection: StubConnection = new StubConnection(_ => true) {
        override def close(): Unit = {
          during.set(connection.getNetworkTimeout)
          super.close()
        }
      }
      val unsupported = new StubConnection(_ => true) {
        override def setNetworkTimeout(executor: Executor, milliseconds: Int): Unit =
          throw new SQLException("not supported")
      }
      for {
        _ <- ConnectionPool.closeConnection(connection)
        // the close goes ahead without the timeout
        _ <- ConnectionPool.closeConnection(unsupported)
        logged <- ZTestLogger.logOutput
      } yield {
        assertTrue(during.get == 15000) &&
        assertTrue(connection.isClosed, unsupported.isClosed) &&
        assertTrue(!logged.exists(_.message() == "Could not close connection"))
      }
    },
    test("invalid config is rejected") {
      val create = ZIO.succeed(new StubConnection(_ => true))
      def isIllegalArgument(exit: Exit[Nothing, ConnectionPool]) =
        exit.causeOption.flatMap(_.dieOption).exists(_.isInstanceOf[IllegalArgumentException])
      for {
        size <- ZIO.scoped[Any](ConnectionPool.create(create, config.copy(size = 0))).exit
        queueSize <- ZIO.scoped[Any](ConnectionPool.create(create, config.copy(queueSize = -1))).exit
        lifetimes <- ZIO.foreach(List(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(365).plusNanos(1))) { d =>
          ZIO.scoped[Any](ConnectionPool.create(create, config.copy(maxConnectionLifetime = d))).exit
        }
        longest <- ZIO.scoped[Any](ConnectionPool.create(create, config.copy(maxConnectionLifetime = Duration.ofDays(365)))).exit
      } yield {
        assertTrue(longest.isSuccess) &&
        assertTrue(isIllegalArgument(size)) &&
        assertTrue(isIllegalArgument(queueSize)) &&
        assertTrue(lifetimes.forall(isIllegalArgument))
      }
    },
  ) @@ TestAspect.timeout(15.seconds)

  // Waits until `n` requests to the pool named `pool` are counted as waiting. A
  // request is counted once it has a slot or is in the queue for one, until its
  // checkout finishes. So when every slot is taken by something that is not
  // itself counted, these requests are in the queue. Pools with the same name
  // share the gauge, so the pool needs a name of its own.
  private def queuedRequests(pool: String, n: Int) =
    (ZIO.yieldNow *> ConnectionPool.metricWaiting(pool).value).repeatUntil(_.value == n.toDouble)

  // Waits until shutdown has closed the pool and is waiting for its slots to
  // be released. Every slot must be in use: a request made before the pool is
  // closed then joins the queue and is interrupted by shutdown, and only one
  // made after fails with Closed. The pool must also have no idle connections
  // for shutdown to close, so that once the pool is closed the shutdown fiber
  // only suspends to wait for slots.
  private def shutdownWaiting(pool: ConnectionPool, closing: Fiber.Runtime[Any, Any]) = {
    val closed = Exit.fail(DatabaseError.Connection.Closed())
    ZIO.scoped[Any](pool.get).fork.flatMap(_.await).repeatUntil(_ == closed) *>
      (ZIO.yieldNow *> closing.status).repeatUntil {
        case _: Fiber.Status.Suspended => true
        case _ => false
      }
  }

  // Waits until some fiber sleeps until `duration` from now on the test clock,
  // so adjusting the clock by `duration` wakes it.
  private def sleepingFor(duration: Duration) = for {
    now <- zio.Clock.instant
    _ <- (ZIO.yieldNow *> TestClock.sleeps).repeatUntil(_.contains(now.plus(duration)))
  } yield ()

  // A connection whose close blocks until `proceed` is released.
  private def blockingClose(closing: CountDownLatch, proceed: CountDownLatch) = new StubConnection(_ => true) {
    override def close(): Unit = {
      closing.countDown()
      val _ = proceed.await(10, TimeUnit.SECONDS)
      super.close()
    }
  }

  // With a queue size of one, fill the queue, then make another request. It is
  // rejected if every slot is taken.
  private def requestWithQueueFull(pool: ConnectionPool, name: String) = for {
    queued <- ZIO.scoped[Any](pool.get).fork
    _ <- queuedRequests(name, 1)
    result <- ZIO.scoped[Any](pool.get).exit
    _ <- queued.interrupt
  } yield result

  // Invalidated connections close in the background, so wait for `n` of them.
  private def awaitClosed(created: zio.UIO[Chunk[StubConnection]], n: Int) =
    (ZIO.yieldNow *> created).repeatUntil(_.count(_.isClosed) >= n)

  private def eventually(condition: => Boolean) = {
    (ZIO.yieldNow *> ZIO.succeed(condition)).repeatUntil(identity)
  }

  private def steppingClock(stepNanos: Long): zio.Clock = {
    val nanos = new AtomicLong(0L)
    zio.Clock.ClockJava(new java.time.Clock {
      override def getZone = java.time.ZoneOffset.UTC
      override def withZone(zone: ZoneId) = this
      override def instant() = Instant.ofEpochSecond(0L, nanos.getAndAdd(stepNanos))
    })
  }

  // `base` with a nanoTime read from `nanos`.
  private def nanoTimeClock(base: zio.Clock, nanos: AtomicLong): zio.Clock = new zio.Clock {
    override def currentTime(unit: => TimeUnit)(implicit trace: Trace) = base.currentTime(unit)
    override def currentTime(unit: => ChronoUnit)(implicit trace: Trace, d: DummyImplicit) = base.currentTime(unit)
    override def currentDateTime(implicit trace: Trace) = base.currentDateTime
    override def instant(implicit trace: Trace) = base.instant
    override def javaClock(implicit trace: Trace) = base.javaClock
    override def localDateTime(implicit trace: Trace) = base.localDateTime
    override def nanoTime(implicit trace: Trace) = ZIO.succeed(nanos.get)
    override def scheduler(implicit trace: Trace) = base.scheduler
    override def sleep(duration: => Duration)(implicit trace: Trace) = base.sleep(duration)
  }

  private def recordingPool(poolConfig: ConnectionPoolConfig = config.copy(size = 1))(connection: Int => StubConnection) = for {
    createdRef <- Ref.make(Chunk.empty[StubConnection])
    create = createdRef.modify { created =>
      val c = connection(created.size)
      (c, created :+ c)
    }
    pool <- ConnectionPool.create(create, poolConfig)
  } yield (pool, createdRef.get)
}
