package zoobie

import zio.test.Assertion.*
import zio.test.ZIOSpecDefault
import zio.test.assert
import zio.test.assertTrue
import zoobie.stub.StubConnection

import scala.util.Try

@SuppressWarnings(Array("org.wartremover.warts.Null"))
object ConnectionProxySpec extends ZIOSpecDefault {

  private def proxy(c: java.sql.Connection) = new ConnectionProxy(c, resetTimeoutMillis = 1500)

  private val isSqlException = isSubtype[java.sql.SQLException](anything)

  override val spec = suite("ConnectionProxy")(
    test("state that is not reset can not be changed") {
      val c = proxy(new StubConnection(_ => true))

      val holdability = Try(c.setHoldability(java.sql.ResultSet.HOLD_CURSORS_OVER_COMMIT))
      val typeMap = Try(c.setTypeMap(new java.util.HashMap[String, Class[?]]))
      val clientInfo = Try(c.setClientInfo("ApplicationName", "changed"))
      val clientInfoProperties = Try(c.setClientInfo(new java.util.Properties))
      val networkTimeout = Try(c.setNetworkTimeout((r: Runnable) => r.run(), 1))
      val catalog = Try(c.setCatalog("a catalog"))
      val schema = Try(c.setSchema("a schema"))

      assert(holdability)(isFailure(isSqlException)) &&
      assert(typeMap)(isFailure(isSqlException)) &&
      assert(clientInfo)(isFailure(isSqlException)) &&
      assert(clientInfoProperties)(isFailure(isSqlException)) &&
      assert(networkTimeout)(isFailure(isSqlException)) &&
      assert(catalog)(isFailure(isSqlException)) &&
      assert(schema)(isFailure(isSqlException))
    },
    test("resets state on close") {
      val stub = new StubConnection(_ => true)
      val c = proxy(stub)
      c.setAutoCommit(false)
      c.setReadOnly(true)
      c.setTransactionIsolation(5)

      val initial = (
        c.getAutoCommit,
        c.isReadOnly,
        c.getTransactionIsolation,
      )

      c.close()

      val after = (
        stub.getAutoCommit,
        stub.isReadOnly,
        stub.getTransactionIsolation,
      )

      assertTrue(initial == (false, true, 5)) &&
      assertTrue(c.isClosed) &&
      assertTrue(after == (true, false, 0))
    },
    test("can not be used after close") {
      val stub = new StubConnection(_ => true)
      val c = proxy(stub)
      c.close()
      val result = Try(c.prepareStatement("SELECT 1"))

      assert(result)(isFailure(isSqlException)) &&
      assertTrue(!c.isValid(0)) &&
      assertTrue(!stub.isClosed)
    },
    test("is closed and not reusable if resetting state fails") {
      val c = proxy(StubConnection.failingReset())
      val result = Try(c.close())
      val again = Try(c.close())

      assertTrue(result.isFailure) &&
      assertTrue(again.isSuccess) &&
      assertTrue(c.isClosed) &&
      assertTrue(!c.reusable)
    },
    test("is reusable only once closed after a successful reset") {
      val c = proxy(new StubConnection(_ => true))
      val beforeClose = c.reusable
      c.close()

      assertTrue(!beforeClose) &&
      assertTrue(c.reusable)
    },
    test("the reset runs only once") {
      lazy val c: ConnectionProxy = proxy(new StubConnection(_ => true) {
        // stands in for another thread closing part way through the reset
        override def clearWarnings(): Unit = c.close()
      })
      c.setAutoCommit(false)
      c.close()

      assertTrue(c.reusable)
    },
    test("is not reusable once aborted or discarded") {
      val (stub, aborts) = abortCounting()
      val aborted = proxy(stub)
      aborted.abort((r: Runnable) => r.run())
      aborted.abort((r: Runnable) => r.run())
      aborted.close()
      val discarded = proxy(new StubConnection(_ => true))
      discarded.discard()
      discarded.close()

      assertTrue(aborts.get == 1) &&
      assertTrue(aborted.isClosed) &&
      assertTrue(discarded.isClosed) &&
      assertTrue(!aborted.reusable) &&
      assertTrue(!discarded.reusable)
    },
    test("is not reusable if aborted while being closed") {
      val aborts = new java.util.concurrent.atomic.AtomicInteger(0)
      lazy val c: ConnectionProxy = proxy(new StubConnection(_ => true) {
        // stands in for another thread aborting part way through the reset
        override def clearWarnings(): Unit = c.abort((r: Runnable) => r.run())
        override def abort(executor: java.util.concurrent.Executor): Unit = {
          val _ = aborts.incrementAndGet()
        }
      })
      c.close()

      assertTrue(aborts.get == 1) &&
      assertTrue(c.isClosed) &&
      assertTrue(!c.reusable)
    },
    test("abort after a successful reset does not reach the underlying connection") {
      val (stub, aborts) = abortCounting()
      val c = proxy(stub)
      c.close()
      c.abort((r: Runnable) => r.run())

      assertTrue(aborts.get == 0) &&
      assertTrue(c.reusable)
    },
    test("the reset is run with a network timeout that is removed afterwards") {
      val during = new java.util.concurrent.atomic.AtomicInteger(-1)
      lazy val stub: StubConnection = new StubConnection(_ => true) {
        override def rollback(): Unit = {
          during.set(stub.getNetworkTimeout)
          super.rollback()
        }
      }
      val c = proxy(stub)
      c.setAutoCommit(false)
      c.close()

      assertTrue(during.get == 1500) &&
      assertTrue(stub.getNetworkTimeout == 0) &&
      assertTrue(c.reusable)
    },
    test("rolls back an open transaction on close") {
      val stub = new StubConnection(_ => true)
      val c = proxy(stub)
      c.setAutoCommit(false)
      c.close()

      assertTrue(stub.numRollbacks == 1) &&
      assertTrue(stub.numCommits == 0)
    },
    test("always rolls back on close when auto-commit is off, even after a commit") {
      // whether a transaction is open is not tracked, the driver skips the rollback in this case
      val stub = new StubConnection(_ => true)
      val c = proxy(stub)
      c.setAutoCommit(false)
      val _ = c.prepareStatement("SELECT 1")
      c.commit()
      c.close()

      assertTrue(stub.numCommits == 1) &&
      assertTrue(stub.numRollbacks == 1)
    },
    test("auto-commit is restored on close only if it was changed") {
      val stub = new CountingConnection
      val transactional = proxy(stub)
      transactional.setAutoCommit(false)
      transactional.commit()
      transactional.close()
      val afterTransactional = stub.autoCommitChanges

      val unchanged = proxy(stub)
      val _ = unchanged.prepareStatement("SELECT 1")
      unchanged.close()

      assertTrue(afterTransactional == Vector(false, true)) &&
      assertTrue(stub.autoCommitChanges == Vector(false, true))
    },
    test("auto-commit is turned on before restoring other state") {
      val calls = new java.util.concurrent.atomic.AtomicReference(Vector.empty[String])
      val stub = new StubConnection(_ => true) {
        override def rollback(): Unit = {
          val _ = calls.updateAndGet(_ :+ "rollback")
          super.rollback()
        }
        override def setAutoCommit(autoCommit: Boolean): Unit = {
          val _ = calls.updateAndGet(_ :+ s"autoCommit=${autoCommit.toString}")
          super.setAutoCommit(autoCommit)
        }
        override def setReadOnly(readOnly: Boolean): Unit = {
          val _ = calls.updateAndGet(_ :+ s"readOnly=${readOnly.toString}")
          super.setReadOnly(readOnly)
        }
      }
      val c = proxy(stub)
      c.setAutoCommit(false)
      c.setReadOnly(true)
      c.close()

      assertTrue(calls.get == Vector("autoCommit=false", "readOnly=true", "rollback", "autoCommit=true", "readOnly=false"))
    },
    test("rolls back on close if the commit failed") {
      val stub = new StubConnection(_ => true) {
        override def commit(): Unit = throw new java.sql.SQLException("broken")
      }
      val c = proxy(stub)
      c.setAutoCommit(false)
      val commit = Try(c.commit())
      c.close()

      assertTrue(commit.isFailure) &&
      assertTrue(stub.numRollbacks == 1)
    },
    test("does not roll back on close when auto-commit is on") {
      val stub = new StubConnection(_ => true)
      val c = proxy(stub)
      c.close()

      assertTrue(stub.numRollbacks == 0)
    },
    test("unwraps to itself before the underlying connection while open") {
      val stub = new StubConnection(_ => true)
      val c = proxy(stub)
      val general = c.unwrap(classOf[java.sql.Connection])
      val underlying = c.unwrap(classOf[StubConnection])
      val wraps = c.isWrapperFor(classOf[java.sql.Connection]) && c.isWrapperFor(classOf[StubConnection])
      c.close()
      val afterClose = Try(c.unwrap(classOf[StubConnection]))

      assertTrue(general eq c) &&
      assertTrue(underlying eq stub) &&
      assertTrue(wraps) &&
      assertTrue(afterClose.isFailure)
    },
  )

  // a connection that counts how many times it is aborted
  private def abortCounting() = {
    val aborts = new java.util.concurrent.atomic.AtomicInteger(0)
    val stub = new StubConnection(_ => true) {
      override def abort(executor: java.util.concurrent.Executor): Unit = {
        val _ = aborts.incrementAndGet()
      }
    }
    (stub, aborts)
  }

  // records every change of auto-commit mode that reaches the underlying connection
  private class CountingConnection extends StubConnection(_ => true) {
    var autoCommitChanges = Vector.empty[Boolean]
    override def setAutoCommit(autoCommit: Boolean): Unit = {
      autoCommitChanges = autoCommitChanges :+ autoCommit
      super.setAutoCommit(autoCommit)
    }
  }
}
