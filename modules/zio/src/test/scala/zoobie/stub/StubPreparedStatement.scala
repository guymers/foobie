package zoobie.stub

import java.io.InputStream
import java.io.Reader
import java.net.URL
import java.sql
import java.sql.Blob
import java.sql.Clob
import java.sql.Connection
import java.sql.Date
import java.sql.NClob
import java.sql.ParameterMetaData
import java.sql.PreparedStatement
import java.sql.Ref
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.RowId
import java.sql.SQLSyntaxErrorException
import java.sql.SQLWarning
import java.sql.SQLXML
import java.sql.Time
import java.sql.Timestamp
import java.util.Calendar

class StubPreparedStatement(fail: Boolean = false) extends PreparedStatement {
  private var closed = false

  override def addBatch(sql: String): Unit = ???
  override def addBatch(): Unit = ???
  override def cancel(): Unit = ???
  override def clearBatch(): Unit = ???

  override def close(): Unit = closed = true
  override def isClosed: Boolean = closed
  override def closeOnCompletion(): Unit = ???
  override def isCloseOnCompletion: Boolean = ???
  override def setPoolable(poolable: Boolean): Unit = ???
  override def isPoolable: Boolean = ???

  override def getWarnings: SQLWarning = ???
  override def clearWarnings(): Unit = ???

  override def executeBatch(): Array[Int] = ???
  override def execute(): Boolean = ???
  override def executeQuery(): ResultSet = {
    if (fail) throw new SQLSyntaxErrorException() else new StubResultSet
  }
  override def executeQuery(sql: String): ResultSet = ???

  override def execute(sql: String, autoGeneratedKeys: Int): Boolean = ???
  override def execute(sql: String): Boolean = ???
  override def execute(sql: String, columnIndexes: Array[Int]): Boolean = ???
  override def execute(sql: String, columnNames: Array[String]): Boolean = ???
  override def executeUpdate(): Int = ???
  override def executeUpdate(sql: String, autoGeneratedKeys: Int): Int = ???
  override def executeUpdate(sql: String, columnIndexes: Array[Int]): Int = ???
  override def executeUpdate(sql: String, columnNames: Array[String]): Int = ???
  override def executeUpdate(sql: String): Int = ???

  override def getConnection: Connection = ???
  override def getFetchDirection: Int = ???
  override def getFetchSize: Int = ???
  override def getGeneratedKeys: ResultSet = ???
  override def getMaxFieldSize: Int = ???
  override def getMaxRows: Int = ???
  override def getMetaData: ResultSetMetaData = ???
  override def getMoreResults: Boolean = ???
  override def getMoreResults(current: Int): Boolean = ???
  override def getParameterMetaData: ParameterMetaData = ???
  override def getQueryTimeout: Int = ???
  override def getResultSetConcurrency: Int = ???
  override def getResultSetHoldability: Int = ???
  override def getResultSet: ResultSet = ???
  override def getResultSetType: Int = ???
  override def getUpdateCount: Int = ???

  override def setArray(parameterIndex: Int, x: sql.Array): Unit = ???
  override def setAsciiStream(parameterIndex: Int, x: InputStream, length: Int): Unit = ???
  override def setAsciiStream(parameterIndex: Int, x: InputStream, length: Long): Unit = ???
  override def setAsciiStream(parameterIndex: Int, x: InputStream): Unit = ???
  override def setBigDecimal(parameterIndex: Int, x: java.math.BigDecimal): Unit = ???
  override def setBinaryStream(parameterIndex: Int, x: InputStream, length: Int): Unit = ???
  override def setBinaryStream(parameterIndex: Int, x: InputStream, length: Long): Unit = ???
  override def setBinaryStream(parameterIndex: Int, x: InputStream): Unit = ???
  override def setBlob(parameterIndex: Int, inputStream: InputStream, length: Long): Unit = ???
  override def setBlob(parameterIndex: Int, inputStream: InputStream): Unit = ???
  override def setBlob(parameterIndex: Int, x: Blob): Unit = ???
  override def setBoolean(parameterIndex: Int, x: Boolean): Unit = ???
  override def setByte(parameterIndex: Int, x: Byte): Unit = ???
  override def setBytes(parameterIndex: Int, x: Array[Byte]): Unit = ???
  override def setCharacterStream(parameterIndex: Int, reader: Reader, length: Int): Unit = ???
  override def setCharacterStream(parameterIndex: Int, reader: Reader, length: Long): Unit = ???
  override def setCharacterStream(parameterIndex: Int, reader: Reader): Unit = ???
  override def setClob(parameterIndex: Int, reader: Reader, length: Long): Unit = ???
  override def setClob(parameterIndex: Int, reader: Reader): Unit = ???
  override def setClob(parameterIndex: Int, x: Clob): Unit = ???
  override def setCursorName(name: String): Unit = ???
  override def setDate(parameterIndex: Int, x: Date, cal: Calendar): Unit = ???
  override def setDate(parameterIndex: Int, x: Date): Unit = ???
  override def setDouble(parameterIndex: Int, x: Double): Unit = ???
  override def setEscapeProcessing(enable: Boolean): Unit = ???
  override def setFetchDirection(direction: Int): Unit = ???
  override def setFetchSize(rows: Int): Unit = ???
  override def setFloat(parameterIndex: Int, x: Float): Unit = ???
  override def setInt(parameterIndex: Int, x: Int): Unit = ???
  override def setLong(parameterIndex: Int, x: Long): Unit = ???
  override def setMaxFieldSize(max: Int): Unit = ???
  override def setMaxRows(max: Int): Unit = ???
  override def setNCharacterStream(parameterIndex: Int, value: Reader, length: Long): Unit = ???
  override def setNCharacterStream(parameterIndex: Int, value: Reader): Unit = ???
  override def setNClob(parameterIndex: Int, reader: Reader, length: Long): Unit = ???
  override def setNClob(parameterIndex: Int, reader: Reader): Unit = ???
  override def setNClob(parameterIndex: Int, value: NClob): Unit = ???
  override def setNString(parameterIndex: Int, value: String): Unit = ???
  override def setNull(parameterIndex: Int, sqlType: Int, typeName: String): Unit = ???
  override def setNull(parameterIndex: Int, sqlType: Int): Unit = ???
  override def setObject(parameterIndex: Int, x: Any, targetSqlType: Int, scaleOrLength: Int): Unit = ???
  override def setObject(parameterIndex: Int, x: Any, targetSqlType: Int): Unit = ???
  override def setObject(parameterIndex: Int, x: Any): Unit = ???
  override def setQueryTimeout(seconds: Int): Unit = ???
  override def setRef(parameterIndex: Int, x: Ref): Unit = ???
  override def setRowId(parameterIndex: Int, x: RowId): Unit = ???
  override def setShort(parameterIndex: Int, x: Short): Unit = ???
  override def setSQLXML(parameterIndex: Int, xmlObject: SQLXML): Unit = ???
  override def setString(parameterIndex: Int, x: String): Unit = ???
  override def setTime(parameterIndex: Int, x: Time, cal: Calendar): Unit = ???
  override def setTime(parameterIndex: Int, x: Time): Unit = ???
  override def setTimestamp(parameterIndex: Int, x: Timestamp, cal: Calendar): Unit = ???
  override def setTimestamp(parameterIndex: Int, x: Timestamp): Unit = ???
  override def setUnicodeStream(parameterIndex: Int, x: InputStream, length: Int): Unit = ???
  override def setURL(parameterIndex: Int, x: URL): Unit = ???

  override def clearParameters(): Unit = ???

  override def isWrapperFor(iface: Class[?]): Boolean = ???
  override def unwrap[T](iface: Class[T]): T = ???
}
