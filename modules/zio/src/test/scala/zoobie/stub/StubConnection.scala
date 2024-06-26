package zoobie.stub

import java.sql
import java.sql.Blob
import java.sql.CallableStatement
import java.sql.Clob
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.NClob
import java.sql.PreparedStatement
import java.sql.SQLWarning
import java.sql.SQLXML
import java.sql.Savepoint
import java.sql.Statement
import java.sql.Struct
import java.util
import java.util.Properties
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@SuppressWarnings(Array("org.wartremover.warts.Null"))
class StubConnection(valid: Int => Boolean) extends Connection {
  private var closed = false

  private var autoCommit = true
  private var readOnly = false
  private var networkTimeout = 0
  private var catalog: String = _
  private var schema: String = _
  private var transactionIsolation = 0

  private val _numCommits = new AtomicInteger(0)
  def numCommits = _numCommits.get()
  private val _numRollbacks = new AtomicInteger(0)
  def numRollbacks = _numRollbacks.get()

  override def commit(): Unit = {
    val _ = _numCommits.updateAndGet(_ + 1)
  }
  override def rollback(): Unit = {
    val _ = _numRollbacks.updateAndGet(_ + 1)
  }
  override def close(): Unit = closed = true
  override def isClosed: Boolean = closed

  override def isValid(timeout: Int): Boolean = valid(timeout)

  override def getAutoCommit: Boolean = autoCommit
  override def setAutoCommit(autoCommit: Boolean): Unit = this.autoCommit = autoCommit
  override def getNetworkTimeout: Int = networkTimeout
  override def setNetworkTimeout(executor: Executor, milliseconds: Int): Unit = networkTimeout = milliseconds
  override def isReadOnly: Boolean = readOnly
  override def setReadOnly(readOnly: Boolean): Unit = this.readOnly = readOnly

  override def getCatalog: String = catalog
  override def setCatalog(catalog: String): Unit = this.catalog = catalog
  override def getSchema: String = schema
  override def setSchema(schema: String): Unit = this.schema = schema
  override def getTransactionIsolation: Int = transactionIsolation
  override def setTransactionIsolation(level: Int): Unit = this.transactionIsolation = level

  override def getMetaData: DatabaseMetaData = ???
  override def getWarnings: SQLWarning = ???
  override def clearWarnings(): Unit = ()

  override def nativeSQL(sql: String): String = ???

  override def getTypeMap: util.Map[String, Class[?]] = ???
  override def setTypeMap(map: util.Map[String, Class[?]]): Unit = ???
  override def getHoldability: Int = ???
  override def setHoldability(holdability: Int): Unit = ???

  override def setSavepoint(): Savepoint = ???
  override def setSavepoint(name: String): Savepoint = ???

  override def rollback(savepoint: Savepoint): Unit = ???
  override def releaseSavepoint(savepoint: Savepoint): Unit = ???

  override def createStatement(): Statement = ???
  override def createStatement(resultSetType: Int, resultSetConcurrency: Int): Statement = ???
  override def createStatement(
    resultSetType: Int,
    resultSetConcurrency: Int,
    resultSetHoldability: Int,
  ): Statement = ???
  override def prepareStatement(sql: String): PreparedStatement = {
    val fail = sql == "INVALID SQL"
    new StubPreparedStatement(fail)
  }
  override def prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement = ???
  override def prepareStatement(
    sql: String,
    resultSetType: Int,
    resultSetConcurrency: Int,
    resultSetHoldability: Int,
  ): PreparedStatement = ???

  override def prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement = ???
  override def prepareStatement(sql: String, columnIndexes: Array[Int]): PreparedStatement = ???
  override def prepareStatement(sql: String, columnNames: Array[String]): PreparedStatement = ???

  override def prepareCall(sql: String): CallableStatement = ???
  override def prepareCall(sql: String, resultSetType: Int, resultSetConcurrency: Int): CallableStatement = ???
  override def prepareCall(
    sql: String,
    resultSetType: Int,
    resultSetConcurrency: Int,
    resultSetHoldability: Int,
  ): CallableStatement = ???

  override def createClob(): Clob = ???
  override def createBlob(): Blob = ???
  override def createNClob(): NClob = ???
  override def createSQLXML(): SQLXML = ???

  override def getClientInfo: Properties = ???
  override def getClientInfo(name: String): String = ???
  override def setClientInfo(name: String, value: String): Unit = ???
  override def setClientInfo(properties: Properties): Unit = ???

  override def createArrayOf(typeName: String, elements: Array[AnyRef]): sql.Array = ???
  override def createStruct(typeName: String, attributes: Array[AnyRef]): Struct = ???
  override def abort(executor: Executor): Unit = ???
  override def unwrap[T](iface: Class[T]): T = ???
  override def isWrapperFor(iface: Class[?]): Boolean = ???
}
