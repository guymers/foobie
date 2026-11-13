package zoobie

import java.sql.Connection
import java.sql.SQLClientInfoException
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.Savepoint
import java.util
import java.util.Properties
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Wraps a pooled connection for a single checkout, resetting any changed
 * connection state on close. Only used by [[ConnectionPool]], every connection
 * that comes out of it will be consumed by foobie.
 *
 * If auto-commit is off then on close the connection is always rolled back, so
 * uncommitted work is never kept. A transaction is only known about if
 * auto-commit is off, one started by executing SQL such as `BEGIN` while
 * auto-commit is on is not rolled back and must be finished by the caller. The
 * rollback does not reach the server if no transaction is open, PostgreSQL
 * checks this itself and MySQL does with `useLocalTransactionState`.
 *
 * Auto-commit, read-only and transaction isolation are restored to the values
 * they had before they were first changed.
 *
 * The reset is run with a network timeout. If the connection has died, the
 * reset fails once the timeout passes instead of blocking forever, and the
 * connection is not reused. Without a reset timeout the connection keeps the
 * network timeout it already has.
 *
 * Once a connection is closed, every method other than `close`, `isClosed`,
 * `isValid` and `abort` throws a [[java.sql.SQLException]]. This ensures a
 * reference that outlives its scope can not use a connection that has been
 * returned to the pool. Objects created from it, such as statements, are not
 * tracked and must not be used after that either.
 *
 * The pool only reuses the underlying connection if [[reusable]] reports that
 * it was reset successfully. A connection that failed to reset, was aborted or
 * was discarded is never reused.
 *
 * Catalog, schema, holdability, type map, client info and network timeout can
 * not be changed as they are not reset. Catalog and schema can not be restored
 * reliably, e.g. PostgreSQL reports only the first schema of the search path
 * and setting it replaces the whole path, so they should be set when the
 * connection is created. State changed with SQL or through an object obtained
 * via `unwrap` is not reset either.
 *
 * Like most JDBC connections this is not thread safe, only its lifecycle is.
 * Once it is closed, calls from another thread fail and the reset runs at most
 * once. An `abort` before the reset completes stops the connection from being
 * reused. After that it does nothing, as the connection may already be checked
 * out again.
 *
 * @param resetTimeoutMillis
 *   the network timeout used while resetting the connection on close, zero
 *   means the network timeout of the connection is left as is
 */
@SuppressWarnings(Array("org.wartremover.warts.Throw", "org.wartremover.warts.Var"))
private[zoobie] class ConnectionProxy(connection: Connection, resetTimeoutMillis: Int) extends Connection {
  import ConnectionProxy.State

  private val state = new AtomicReference[State](State.Open)

  // the original values of any state that was changed
  private var autoCommit: Option[Boolean] = None
  private var readOnly: Option[Boolean] = None
  private var transactionIsolation: Option[Int] = None

  private def checkOpen(): Unit = {
    if (state.get != State.Open) throw new SQLException("Connection is closed", "08003")
  }

  private def conn: Connection = {
    checkOpen()
    connection
  }

  override def close(): Unit = {
    if (state.compareAndSet(State.Open, State.Closing)) {
      try {
        // Setting the timeout is local in JDBC drivers, they do not use the executor.
        // With no reset timeout the connection keeps its own, which may not be zero.
        val networkTimeout = if (resetTimeoutMillis > 0) {
          val previous = connection.getNetworkTimeout
          connection.setNetworkTimeout(ConnectionProxy.directExecutor, resetTimeoutMillis)
          Some(previous)
        } else None
        if (!connection.getAutoCommit) {
          connection.rollback()
        }
        // Restore auto-commit before other state: drivers may run SQL to change read-only or isolation,
        // and with auto-commit still off that SQL could open a transaction nobody closes.
        autoCommit.foreach(connection.setAutoCommit)
        readOnly.foreach(connection.setReadOnly)
        transactionIsolation.foreach(connection.setTransactionIsolation)
        connection.clearWarnings()
        networkTimeout.foreach(connection.setNetworkTimeout(ConnectionProxy.directExecutor, _))
        val _ = state.compareAndSet(State.Closing, State.Reusable)
      } finally {
        // If the reset threw, the state is still closing and the connection can not be reused.
        // After a successful reset, or an abort during it, the state has already moved on and this does nothing.
        val _ = state.compareAndSet(State.Closing, State.Unusable)
      }
    }
  }

  /**
   * If the underlying connection is safe to reuse, true only once this has been
   * closed, its state was reset successfully and it was not aborted or
   * discarded.
   */
  private[zoobie] def reusable: Boolean = state.get == State.Reusable

  /**
   * Mark this proxy as closed without resetting the connection state.
   */
  private[zoobie] def discard(): Unit = state.set(State.Unusable)

  override def isClosed = state.get != State.Open

  override def createStatement() = conn.createStatement()
  override def createStatement(resultSetType: Int, concurrency: Int) = conn.createStatement(resultSetType, concurrency)
  override def createStatement(resultSetType: Int, concurrency: Int, holdability: Int) = conn.createStatement(resultSetType, concurrency, holdability)
  override def prepareCall(sql: String) = conn.prepareCall(sql)
  override def prepareCall(sql: String, resultSetType: Int, concurrency: Int) = conn.prepareCall(sql, resultSetType, concurrency)
  override def prepareCall(sql: String, resultSetType: Int, concurrency: Int, holdability: Int) = conn.prepareCall(sql, resultSetType, concurrency, holdability)
  override def prepareStatement(sql: String) = conn.prepareStatement(sql)
  override def prepareStatement(sql: String, autoGeneratedKeys: Int) = conn.prepareStatement(sql, autoGeneratedKeys)
  override def prepareStatement(sql: String, resultSetType: Int, concurrency: Int) = conn.prepareStatement(sql, resultSetType, concurrency)
  override def prepareStatement(sql: String, resultSetType: Int, concurrency: Int, holdability: Int) =
    conn.prepareStatement(sql, resultSetType, concurrency, holdability)
  override def prepareStatement(sql: String, columnIndexes: Array[Int]) = conn.prepareStatement(sql, columnIndexes)
  override def prepareStatement(sql: String, columnNames: Array[String]) = conn.prepareStatement(sql, columnNames)

  override def getMetaData = conn.getMetaData

  override def commit() = conn.commit()
  override def rollback() = conn.rollback()
  override def rollback(savepoint: Savepoint) = conn.rollback(savepoint)

  override def getAutoCommit = conn.getAutoCommit
  override def setAutoCommit(autoCommit: Boolean): Unit = {
    if (this.autoCommit == None) {
      this.autoCommit = Some(conn.getAutoCommit)
    }
    conn.setAutoCommit(autoCommit)
  }

  override def isReadOnly = conn.isReadOnly
  override def setReadOnly(readOnly: Boolean): Unit = {
    if (this.readOnly == None) {
      this.readOnly = Some(conn.isReadOnly)
    }
    conn.setReadOnly(readOnly)
  }

  override def getTransactionIsolation = conn.getTransactionIsolation
  override def setTransactionIsolation(level: Int): Unit = {
    if (this.transactionIsolation == None) {
      this.transactionIsolation = Some(conn.getTransactionIsolation)
    }
    conn.setTransactionIsolation(level)
  }

  override def getCatalog = conn.getCatalog
  override def setCatalog(catalog: String): Unit = {
    throw new SQLFeatureNotSupportedException("setCatalog not supported")
  }

  override def getSchema = conn.getSchema
  override def setSchema(schema: String): Unit = {
    throw new SQLFeatureNotSupportedException("setSchema not supported")
  }

  override def getNetworkTimeout = conn.getNetworkTimeout
  override def setNetworkTimeout(executor: Executor, milliseconds: Int): Unit = {
    throw new SQLFeatureNotSupportedException("setNetworkTimeout not supported")
  }

  // check the proxy itself before delegating: unwrapping to an interface it also implements,
  // such as `Connection`, would otherwise return the underlying connection and bypass the proxy
  override def isWrapperFor(iface: Class[?]) = {
    checkOpen()
    iface.isInstance(this) || connection.isWrapperFor(iface)
  }
  override def unwrap[T](iface: Class[T]) = {
    checkOpen()
    if (iface.isInstance(this)) iface.cast(this) else connection.unwrap(iface)
  }

  override def nativeSQL(sql: String) = conn.nativeSQL(sql)

  override def getWarnings = conn.getWarnings
  override def clearWarnings() = conn.clearWarnings()

  override def getTypeMap = conn.getTypeMap
  override def setTypeMap(map: util.Map[String, Class[?]]): Unit = {
    throw new SQLFeatureNotSupportedException("setTypeMap not supported")
  }

  override def getHoldability = conn.getHoldability
  override def setHoldability(holdability: Int): Unit = {
    throw new SQLFeatureNotSupportedException("setHoldability not supported")
  }

  override def setSavepoint() = conn.setSavepoint()
  override def setSavepoint(name: String) = conn.setSavepoint(name)
  override def releaseSavepoint(savepoint: Savepoint) = conn.releaseSavepoint(savepoint)

  override def createClob() = conn.createClob()
  override def createBlob() = conn.createBlob()
  override def createNClob() = conn.createNClob()
  override def createSQLXML() = conn.createSQLXML()

  override def isValid(timeout: Int): Boolean = state.get == State.Open && connection.isValid(timeout)

  override def setClientInfo(name: String, value: String): Unit = {
    throw new SQLClientInfoException("setClientInfo not supported", java.util.Map.of[String, java.sql.ClientInfoStatus]())
  }
  override def setClientInfo(properties: Properties): Unit = {
    throw new SQLClientInfoException("setClientInfo not supported", java.util.Map.of[String, java.sql.ClientInfoStatus]())
  }
  override def getClientInfo = conn.getClientInfo
  override def getClientInfo(name: String) = conn.getClientInfo(name)

  override def createArrayOf(typeName: String, elements: Array[AnyRef]) = conn.createArrayOf(typeName, elements)
  override def createStruct(typeName: String, attributes: Array[AnyRef]) = conn.createStruct(typeName, attributes)

  override def abort(executor: Executor): Unit = {
    // a connection that was reset may already be checked out again, it must not be aborted
    val previous = state.getAndUpdate(s => if (s == State.Reusable) s else State.Unusable)
    if (previous == State.Open || previous == State.Closing) {
      connection.abort(executor)
    }
  }
}
object ConnectionProxy {
  private val directExecutor: Executor = (r: Runnable) => r.run()

  private sealed trait State
  private object State {
    case object Open extends State
    // the state of the underlying connection is being reset
    case object Closing extends State
    // closed and reset, the underlying connection can go back into the pool
    case object Reusable extends State
    // closed without a successful reset, or aborted or discarded
    case object Unusable extends State
  }
}
