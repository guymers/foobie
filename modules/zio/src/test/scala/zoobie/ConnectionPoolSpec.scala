package zoobie

import zio.Chunk
import zio.Exit
import zio.Fiber
import zio.LogLevel
import zio.Promise
import zio.Ref
import zio.Schedule
import zio.Scope
import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.TestClock
import zio.test.ZIOSpecDefault
import zio.test.ZTestLogger
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
      def isTransient(error: DatabaseError.Connection) = error match {
        case _: DatabaseError.Connection.Transient => true
        case _ => false
      }

      def alwaysFails: ZIO[Any, DatabaseError.Connection, java.sql.Connection] = ZIO.fail(
        DatabaseError.Connection.Transient(new SQLTransientConnectionException),
      )
      def alwaysSuccess = ZIO.succeed(new StubConnection(_ => true))

      for {
        failing <- Ref.make(true)
        create = failing.get.flatMap { fail => if (fail) alwaysFails else alwaysSuccess }
        results <- ZIO.scoped[Any](for {
          pool <- ConnectionPool.create(create, config)
          failed <- ZIO.scoped[Any] { pool.get }.either
          _ <- failing.set(false)
          recovered <- ZIO.scoped[Any] {
            pool.get.retry(Schedule.recurWhile[DatabaseError.Connection]({
              case _: DatabaseError.Connection.Transient => true
              case _ => false
            }))
          }.either
        } yield (failed, recovered))
      } yield {
        val (failed, recovered) = results
        assertTrue(failed.fold(isTransient, _ => false)) &&
        assertTrue(recovered.isRight)
      }
    },
    test("a failed acquire releases its demand") {
      for {
        attempts <- Ref.make(0)
        create = attempts.updateAndGet(_ + 1).flatMap { i =>
          if (i <= 2) {
            ZIO.fail(DatabaseError.Connection.Transient(new SQLTransientConnectionException))
          } else {
            ZIO.succeed(new StubConnection(_ => true))
          }
        }
        pool <- ConnectionPool.create(create, config.copy(size = 1, queueSize = 0))
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
    test("closing the pool closes all connections") {
      for {
        _ <- ZIO.unit
        connections = (1 to config.size).map(_ => new StubConnection(_ => true))
        ref <- Ref.make(connections)
        create = ref.modify(conns => (conns.head, conns.drop(1)))
        numClosed <- ZIO.scoped[Any](for {
          pool <- ConnectionPool.create(create, config)
          // holding every connection at once, twice, reuses them instead of creating more
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
    test("closing the pool does not wait for connections in use") {
      for {
        _ <- ZIO.unit
        connections = (1 to config.size).map(_ => new StubConnection(_ => true))
        ref <- Ref.make(connections)
        create = ref.modify(conns => (conns.head, conns.drop(1)))
        scope <- Scope.make
        pool <- scope.extend[Any](ConnectionPool.create(create, config.copy(size = 2)))
        held1 <- Promise.make[Nothing, Unit]
        held2 <- Promise.make[Nothing, Unit]
        running1 <- ZIO.scoped[Any] { pool.get *> held1.succeed(()) *> ZIO.sleep(1.second) }.as(1).fork
        running2 <- ZIO.scoped[Any] { pool.get *> held2.succeed(()) *> ZIO.sleep(2.seconds) }.as(2).fork
        _ <- held1.await *> held2.await
        waiting1 <- ZIO.scoped[Any] { pool.get *> ZIO.sleep(3.seconds) }.as(3).fork
        _ <- waitingForConnection(waiting1)
        _ <- scope.close(Exit.unit)
        // connections in use are closed when they are returned
        closedEarly = connections.exists(_.isClosed)
        // fails without waiting for a connection to be returned
        afterClose <- ZIO.scoped[Any](pool.get).exit
        _ <- TestClock.adjust(2.seconds)
        running1Result <- running1.await
        running2Result <- running2.await
        waiting1Result <- waiting1.await
      } yield {
        assertTrue(!closedEarly) &&
        assertTrue(running1Result == Exit.succeed(1)) &&
        assertTrue(running2Result == Exit.succeed(2)) &&
        assertTrue(waiting1Result.isInterruptedOnly) &&
        assertTrue(afterClose.isInterruptedOnly) &&
        assertTrue(connections.take(2).forall(_.isClosed))
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
    test("a queue size of Int.MaxValue does not reject requests") {
      for {
        _ <- ZIO.unit
        create = ZIO.succeed(new StubConnection(_ => true))
        pool <- ConnectionPool.create(create, config.copy(size = 1, queueSize = Int.MaxValue))
        acquired <- ZIO.scoped[Any](pool.get).exit
      } yield {
        assertTrue(acquired.isSuccess)
      }
    },
    test("a lifetime too large for nanoseconds never expires") {
      for {
        createdRef <- Ref.make(0)
        create = createdRef.update(_ + 1).as(new StubConnection(_ => true))
        pool <- ConnectionPool.create(
          create,
          config.copy(size = 1, maxConnectionLifetime = Duration.ofSeconds(Long.MaxValue)),
        )
        first <- ZIO.scoped[Any](pool.get).exit
        _ <- TestClock.adjust(3650.days)
        second <- ZIO.scoped[Any](pool.get).exit
        created <- createdRef.get
      } yield {
        assertTrue(first.isSuccess, second.isSuccess) &&
        assertTrue(created == 1)
      }
    },
    test("discarding a connection past its lifetime does not validate it") {
      for {
        _ <- ZIO.unit
        validations = new java.util.concurrent.atomic.AtomicInteger(0)
        create = ZIO.succeed(new StubConnection(_ => {
          val _ = validations.incrementAndGet()
          true
        }))
        pool <- ConnectionPool.create(create, config.copy(size = 1))
        _ <- ZIO.scoped[Any](pool.get)
        _ <- TestClock.adjust(config.maxConnectionLifetime.plusSeconds(1))
        _ <- ZIO.scoped[Any](pool.get)
      } yield {
        assertTrue(validations.get == 0)
      }
    },
    test("a queue size of zero only rejects requests that would have to wait") {
      for {
        _ <- ZIO.unit
        create = ZIO.succeed(new StubConnection(_ => true))
        pool <- ConnectionPool.create(create, config.copy(size = 2, queueSize = 0))
        _ <- pool.get
        _ <- pool.get
        rejected <- ZIO.scoped[Any](pool.get).exit
      } yield {
        assertTrue(rejected == Exit.fail(DatabaseError.Connection.Rejected(0)))
      }
    },
    test("connections are returned to callers after a rejection") {
      for {
        _ <- ZIO.unit
        create = ZIO.succeed(new StubConnection(_ => true))
        pool <- ConnectionPool.create(create, config.copy(size = 1, queueSize = 0))
        _ <- ZIO.scoped[Any](pool.get)
        _ <- ZIO.scoped[Any](pool.get)
        held <- Promise.make[Nothing, Unit]
        done <- Promise.make[Nothing, Unit]
        holder <- ZIO.scoped[Any](pool.get *> held.succeed(()) *> done.await).fork
        _ <- held.await
        rejected <- ZIO.scoped[Any](pool.get).exit
        _ <- done.succeed(())
        _ <- holder.join
        acquired <- ZIO.scoped[Any](pool.get).exit
      } yield {
        assertTrue(rejected == Exit.fail(DatabaseError.Connection.Rejected(0))) &&
        assertTrue(acquired.isSuccess)
      }
    },
    test("a connection created for a request is used even if it is past its lifetime") {
      for {
        createdRef <- Ref.make(0)
        create = createdRef.update(_ + 1).as(new StubConnection(_ => true))
        pool <- ConnectionPool.create(
          create,
          config.copy(size = 1, maxConnectionLifetime = java.time.Duration.ofNanos(1)),
        )
        result <- ZIO.scoped[Any](pool.get).exit
        created <- createdRef.get
      } yield {
        assertTrue(result.isSuccess) &&
        assertTrue(created == 1)
      }
    } @@ TestAspect.withLiveClock,
    test("a connection being returned to the pool still counts towards the queue size") {
      val validating = new java.util.concurrent.CountDownLatch(1)
      val proceed = new java.util.concurrent.CountDownLatch(1)
      val create = ZIO.succeed(new StubConnection(_ => {
        validating.countDown()
        val _ = proceed.await(10, TimeUnit.SECONDS)
        true
      }))
      for {
        pool <- ConnectionPool.create(create, config.copy(size = 1, queueSize = 0))
        // a failure validates the connection before it is returned to the pool
        holder <- ZIO.scoped[Any](pool.get *> ZIO.fail(new RuntimeException("boom"))).exit.fork
        validatingStarted <- ZIO.attemptBlocking(validating.await(10, TimeUnit.SECONDS))
        rejected <- ZIO.scoped[Any](pool.get).exit
        _ <- ZIO.succeed(proceed.countDown())
        _ <- holder.join
        acquired <- ZIO.scoped[Any](pool.get).exit
      } yield {
        assertTrue(validatingStarted) &&
        assertTrue(rejected == Exit.fail(DatabaseError.Connection.Rejected(0))) &&
        assertTrue(acquired.isSuccess)
      }
    },
    test("a connection is reset before it is returned when finalizers run in parallel") {
      val resetting = new java.util.concurrent.CountDownLatch(1)
      val proceed = new java.util.concurrent.CountDownLatch(1)
      val create = ZIO.succeed(new StubConnection(_ => true) {
        override def clearWarnings(): Unit = {
          resetting.countDown()
          val _ = proceed.await(10, TimeUnit.SECONDS)
        }
      })
      for {
        pool <- ConnectionPool.create(create, config.copy(size = 1, queueSize = 1))
        holder <- ZIO.scoped[Any](ZIO.parallelFinalizers(pool.get)).fork
        resettingStarted <- ZIO.attemptBlocking(resetting.await(10, TimeUnit.SECONDS))
        // can only get the connection once the reset has finished
        next <- ZIO.scoped[Any](pool.get).fork
        early <- next.join.timeout(200.millis)
        _ <- ZIO.succeed(proceed.countDown())
        _ <- holder.join
        _ <- next.join
      } yield {
        assertTrue(resettingStarted) &&
        assertTrue(early.isEmpty)
      }
    } @@ TestAspect.withLiveClock,
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
    test("a request that gave up waiting does not interrupt the next request") {
      for {
        _ <- ZIO.unit
        create = ZIO.succeed(new StubConnection(_ => true))
        pool <- ConnectionPool.create(create, config.copy(size = 1, alwaysInvalidateOnFailure = true))
        held <- Promise.make[Nothing, Unit]
        finish <- Promise.make[Nothing, Unit]
        holder <- ZIO.scoped[Any](pool.get *> held.succeed(()) *> finish.await *> ZIO.fail(new RuntimeException("boom"))).exit.fork
        _ <- held.await
        waiter <- ZIO.scoped[Any](pool.get).fork
        _ <- waitingForConnection(waiter)
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
        pool <- ConnectionPool.create(create, config.copy(size = 1, queueSize = 0))
        _ <- ZIO.foreachDiscard(1 to 500) { _ =>
          ZIO.scoped[Any](pool.get *> ZIO.never).fork.flatMap(f => ZIO.yieldNow *> f.interrupt)
        }
        acquired <- ZIO.scoped[Any](pool.get).exit
      } yield {
        assertTrue(acquired.isSuccess)
      }
    },
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
    test("failures with no request to return them to are logged") {
      for {
        tuple <- recordingPool() { _ =>
          new StubConnection(_ => true) {
            override def isValid(timeout: Int): Boolean = throw new java.sql.SQLException("validation failed")
            override def close(): Unit = throw new java.sql.SQLException("close failed")
          }
        }
        (pool, _) = tuple
        // a failure validates the connection, which fails, so it is discarded and closing it fails
        _ <- ZIO.scoped[Any](pool.get *> ZIO.fail(new RuntimeException("boom"))).either
        logged <- ZTestLogger.logOutput
      } yield {
        def failedWith(cause: zio.Cause[Any], message: String) = cause.failureOption.exists {
          case t: Throwable => t.getMessage == message
          case _ => false
        }
        val warnings = logged.filter(_.logLevel == LogLevel.Warning)
        assertTrue(warnings.exists(e => e.message() == "Could not validate connection" && failedWith(e.cause, "validation failed"))) &&
        assertTrue(warnings.exists(e => e.message() == "Could not close connection" && failedWith(e.cause, "close failed")))
      }
    },
    test("a request can give up while its connection is being created") {
      for {
        connecting <- Promise.make[Nothing, Unit]
        opened <- Promise.make[Nothing, Unit]
        createdRef <- Ref.make(0)
        create = connecting.succeed(()) *> opened.await *> createdRef.update(_ + 1).as(new StubConnection(_ => true))
        pool <- ConnectionPool.create(create, config.copy(size = 1, queueSize = 0))
        request <- ZIO.scoped[Any](pool.get).fork
        _ <- connecting.await
        // does not wait for the connection to be created
        gaveUp <- request.interrupt
        // the attempt still holds the only connection
        rejected <- ZIO.scoped[Any](pool.get).exit
        _ <- opened.succeed(())
        next <- ZIO.scoped[Any](pool.get.retryWhile(_.isInstanceOf[DatabaseError.Connection.Rejected])).exit
        created <- createdRef.get
      } yield {
        assertTrue(gaveUp.isInterruptedOnly) &&
        assertTrue(rejected == Exit.fail(DatabaseError.Connection.Rejected(0))) &&
        assertTrue(next.isSuccess) &&
        assertTrue(created == 1)
      }
    },
    test("a request does not wait for an expired connection to close") {
      val closing = new java.util.concurrent.CountDownLatch(1)
      val proceed = new java.util.concurrent.CountDownLatch(1)
      for {
        tuple <- recordingPool() { i =>
          if (i == 0) {
            new StubConnection(_ => true) {
              override def close(): Unit = {
                closing.countDown()
                val _ = proceed.await(5, TimeUnit.SECONDS)
                super.close()
              }
            }
          } else new StubConnection(_ => true)
        }
        (pool, created) = tuple
        _ <- ZIO.scoped[Any](pool.get)
        _ <- TestClock.adjust(config.maxConnectionLifetime.plusSeconds(1))
        next <- ZIO.scoped[Any](pool.get).exit
        created <- created
        // waiting for the close would only return once it gave up waiting to proceed
        closedOnReturn = created.headOption.exists(_.isClosed)
        closeStarted <- ZIO.attemptBlocking(closing.await(10, TimeUnit.SECONDS))
        _ <- ZIO.succeed(proceed.countDown())
        _ <- eventually(created.headOption.exists(_.isClosed))
      } yield {
        assertTrue(next.isSuccess) &&
        assertTrue(!closedOnReturn) &&
        assertTrue(closeStarted) &&
        assertTrue(created.size == 2)
      }
    },
    test("invalid config is rejected") {
      val create = ZIO.succeed(new StubConnection(_ => true))
      def isIllegalArgument(exit: Exit[Nothing, ConnectionPool]) =
        exit.causeOption.flatMap(_.dieOption).exists(_.isInstanceOf[IllegalArgumentException])
      for {
        size <- ZIO.scoped[Any](ConnectionPool.create(create, config.copy(size = 0))).exit
        queueSize <- ZIO.scoped[Any](ConnectionPool.create(create, config.copy(queueSize = -1))).exit
        lifetimes <- ZIO.foreach(List(java.time.Duration.ZERO, java.time.Duration.ofSeconds(-1))) { d =>
          ZIO.scoped[Any](ConnectionPool.create(create, config.copy(maxConnectionLifetime = d))).exit
        }
      } yield {
        assertTrue(isIllegalArgument(size)) &&
        assertTrue(isIllegalArgument(queueSize)) &&
        assertTrue(lifetimes.forall(isIllegalArgument))
      }
    },
  ) @@ TestAspect.timeout(15.seconds)

  /**
   * Waits until a request is blocked waiting for a connection. A request only
   * suspends once it is waiting for a connection to be returned.
   */
  private def waitingForConnection(request: Fiber.Runtime[Any, Any]) = {
    (ZIO.yieldNow *> request.status).repeatUntil {
      case _: Fiber.Status.Suspended => true
      case _ => false
    }
  }

  /** Waits until a condition, changed by another fiber, holds. */
  private def eventually(condition: => Boolean) =
    (ZIO.yieldNow *> ZIO.succeed(condition)).repeatUntil(identity)

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
