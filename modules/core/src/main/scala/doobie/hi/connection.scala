// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.hi

import cats.Foldable
import cats.data.Ior
import cats.effect.kernel.syntax.monadCancel.*
import cats.syntax.apply.*
import cats.syntax.foldable.*
import doobie.FC
import doobie.FCS
import doobie.FDMD
import doobie.FPS
import doobie.FRS
import doobie.FS
import doobie.enumerated.AutoGeneratedKeys
import doobie.enumerated.Holdability
import doobie.enumerated.Nullability
import doobie.enumerated.ResultSetConcurrency
import doobie.enumerated.ResultSetType
import doobie.enumerated.TransactionIsolation
import doobie.free.callablestatement.CallableStatementIO
import doobie.free.connection.ConnectionIO
import doobie.free.databasemetadata.DatabaseMetaDataIO
import doobie.free.preparedstatement.PreparedStatementIO
import doobie.free.statement.StatementIO
import doobie.util.Get
import doobie.util.Put
import doobie.util.Read
import doobie.util.Write
import doobie.util.analysis.Analysis
import doobie.util.analysis.ColumnMeta
import doobie.util.analysis.ParameterMeta
import doobie.util.stream.repeatEvalChunks
import fs2.Stream

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Savepoint
import scala.collection.Factory
import scala.jdk.CollectionConverters.*

/**
 * Module of high-level constructors for `ConnectionIO` actions.
 * @group Modules
 */
object connection {

  /** @group Lifting */
  def delay[A](a: => A): ConnectionIO[A] =
    FC.delay(a)

  private def liftStream[A: Read](
    chunkSize: Int,
    create: ConnectionIO[PreparedStatement],
    prep: PreparedStatementIO[Unit],
    exec: PreparedStatementIO[ResultSet],
  ): Stream[ConnectionIO, A] = {

    def prepared(ps: PreparedStatement): Stream[ConnectionIO, PreparedStatement] =
      Stream.eval[ConnectionIO, PreparedStatement] {
        val fs = FPS.setFetchSize(chunkSize)
        FC.embed(ps, fs *> prep).map(_ => ps)
      }

    def unrolled(rs: ResultSet): Stream[ConnectionIO, A] =
      repeatEvalChunks(FC.embed(rs, resultset.getNextChunk[A](chunkSize)))

    val preparedStatement: Stream[ConnectionIO, PreparedStatement] =
      Stream.bracket(create)(FC.embed(_, FPS.close)).flatMap(prepared)

    def results(ps: PreparedStatement): Stream[ConnectionIO, A] =
      Stream.bracket(FC.embed(ps, exec))(FC.embed(_, FRS.close)).flatMap(unrolled)

    preparedStatement.flatMap(results)

  }

  /**
   * Construct a prepared statement from the given `sql`, configure it with the
   * given `PreparedStatementIO` action, and return results via a `Stream`.
   * @group Prepared Statements
   */
  def stream[A: Read](sql: String, prep: PreparedStatementIO[Unit], chunkSize: Int): Stream[ConnectionIO, A] =
    liftStream(chunkSize, FC.prepareStatement(sql), prep, FPS.executeQuery)

  /**
   * Construct a prepared update statement with the given return columns (and
   * readable destination type `A`) and sql source, configure it with the given
   * `PreparedStatementIO` action, and return the generated key results via a
   * `Stream`.
   * @group Prepared Statements
   */
  def updateWithGeneratedKeys[A: Read](cols: List[String])(
    sql: String,
    prep: PreparedStatementIO[Unit],
    chunkSize: Int,
  ): Stream[ConnectionIO, A] =
    liftStream(chunkSize, FC.prepareStatement(sql, cols.toArray), prep, FPS.executeUpdate *> FPS.getGeneratedKeys)

  /** @group Prepared Statements */
  def updateManyWithGeneratedKeys[F[_]: Foldable, A: Write, B: Read](cols: List[String])(
    sql: String,
    prep: PreparedStatementIO[Unit],
    fa: F[A],
    chunkSize: Int,
  ): Stream[ConnectionIO, B] =
    liftStream[B](
      chunkSize,
      FC.prepareStatement(sql, cols.toArray),
      prep,
      HPS.addBatchesAndExecute(fa) *> FPS.getGeneratedKeys,
    )

  def updateManyReturningGeneratedKeys[F[_]: Foldable, A: Write, K: Read](cols: List[String])(
    sql: String,
    fa: F[A],
  )(implicit B: Factory[K, F[K]]) = {
    if (fa.isEmpty) {
      FC.delay(B.newBuilder.result())
    } else {
      val readRows = FPS.getGeneratedKeys.bracket { rs =>
        FPS.embed(rs, resultset.build[F, K])
      }(FPS.embed(_, FRS.close))

      prepareStatementS(sql, cols)(HPS.addBatches(fa) *> FPS.executeBatch *> readRows)
    }
  }

  /** @group Transaction Control */
  val commit: ConnectionIO[Unit] =
    FC.commit

  /**
   * Construct an analysis for the provided `sql` query, given writable
   * parameter type `A` and readable resultset row type `B`.
   */
  def prepareQueryAnalysis[A: Write, B: Read](sql: String): ConnectionIO[Analysis] =
    prepareAnalysis(sql, HPS.getParameterMappings[A], HPS.getColumnMappings[B])

  def prepareQueryAnalysis0[B: Read](sql: String): ConnectionIO[Analysis] =
    prepareAnalysis(sql, FPS.pure(Nil), HPS.getColumnMappings[B])

  def prepareUpdateAnalysis[A: Write](sql: String): ConnectionIO[Analysis] =
    prepareAnalysis(sql, HPS.getParameterMappings[A], FPS.pure(Nil))

  def prepareUpdateAnalysis0(sql: String): ConnectionIO[Analysis] =
    prepareAnalysis(sql, FPS.pure(Nil), FPS.pure(Nil))

  private def prepareAnalysis(
    sql: String,
    params: PreparedStatementIO[List[Ior[(Put[?], Nullability.NullabilityKnown), ParameterMeta]]],
    columns: PreparedStatementIO[List[Ior[(Get[?], Nullability.NullabilityKnown), ColumnMeta]]],
  ) = {
    val mappings = prepareStatement(sql) {
      (params, columns).tupled
    }
    (HC.getMetaData(FDMD.getDriverName), mappings).mapN { case (driver, (p, c)) =>
      Analysis(driver, sql, p, c)
    }
  }

  /** @group Statements */
  def createStatement[A](k: StatementIO[A]): ConnectionIO[A] =
    FC.createStatement.bracket(s => FC.embed(s, k))(s => FC.embed(s, FS.close))

  /** @group Statements */
  def createStatement[A](rst: ResultSetType, rsc: ResultSetConcurrency)(k: StatementIO[A]): ConnectionIO[A] =
    FC.createStatement(rst.toInt, rsc.toInt).bracket(s => FC.embed(s, k))(s => FC.embed(s, FS.close))

  /** @group Statements */
  def createStatement[A](
    rst: ResultSetType,
    rsc: ResultSetConcurrency,
    rsh: Holdability,
  )(k: StatementIO[A]): ConnectionIO[A] =
    FC.createStatement(rst.toInt, rsc.toInt, rsh.toInt).bracket(s => FC.embed(s, k))(s => FC.embed(s, FS.close))

  /** @group Connection Properties */
  val getCatalog: ConnectionIO[String] =
    FC.getCatalog

  /** @group Connection Properties */
  def getClientInfo(key: String): ConnectionIO[Option[String]] =
    FC.getClientInfo(key).map(Option(_))

  /** @group Connection Properties */
  val getClientInfo: ConnectionIO[Map[String, String]] =
    FC.getClientInfo.map(_.asScala.toMap)

  /** @group Connection Properties */
  val getHoldability: ConnectionIO[Holdability] =
    FC.getHoldability.flatMap(Holdability.fromIntF[ConnectionIO])

  /** @group Connection Properties */
  def getMetaData[A](k: DatabaseMetaDataIO[A]): ConnectionIO[A] =
    FC.getMetaData.flatMap(s => FC.embed(s, k))

  /** @group Transaction Control */
  val getTransactionIsolation: ConnectionIO[TransactionIsolation] =
    FC.getTransactionIsolation.flatMap(TransactionIsolation.fromIntF[ConnectionIO])

  /** @group Connection Properties */
  val isReadOnly: ConnectionIO[Boolean] =
    FC.isReadOnly

  /** @group Callable Statements */
  def prepareCall[A](
    sql: String,
    rst: ResultSetType,
    rsc: ResultSetConcurrency,
  )(k: CallableStatementIO[A]): ConnectionIO[A] =
    FC.prepareCall(sql, rst.toInt, rsc.toInt).bracket(s => FC.embed(s, k))(s => FC.embed(s, FCS.close))

  /** @group Callable Statements */
  def prepareCall[A](sql: String)(k: CallableStatementIO[A]): ConnectionIO[A] =
    FC.prepareCall(sql).bracket(s => FC.embed(s, k))(s => FC.embed(s, FCS.close))

  /** @group Callable Statements */
  def prepareCall[A](
    sql: String,
    rst: ResultSetType,
    rsc: ResultSetConcurrency,
    rsh: Holdability,
  )(k: CallableStatementIO[A]): ConnectionIO[A] =
    FC.prepareCall(sql, rst.toInt, rsc.toInt, rsh.toInt).bracket(s => FC.embed(s, k))(s => FC.embed(s, FCS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](
    sql: String,
    rst: ResultSetType,
    rsc: ResultSetConcurrency,
  )(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql, rst.toInt, rsc.toInt).bracket(s => FC.embed(s, k))(s => FC.embed(s, FPS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](sql: String)(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql).bracket(s => FC.embed(s, k))(s => FC.embed(s, FPS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](
    sql: String,
    rst: ResultSetType,
    rsc: ResultSetConcurrency,
    rsh: Holdability,
  )(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql, rst.toInt, rsc.toInt, rsh.toInt).bracket(s => FC.embed(s, k))(s => FC.embed(s, FPS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](sql: String, agk: AutoGeneratedKeys)(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql, agk.toInt).bracket(s => FC.embed(s, k))(s => FC.embed(s, FPS.close))

  /** @group Prepared Statements */
  def prepareStatementI[A](sql: String, columnIndexes: List[Int])(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql, columnIndexes.toArray).bracket(s => FC.embed(s, k))(s => FC.embed(s, FPS.close))

  /** @group Prepared Statements */
  def prepareStatementS[A](sql: String, columnNames: List[String])(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql, columnNames.toArray).bracket(s => FC.embed(s, k))(s => FC.embed(s, FPS.close))

  /** @group Transaction Control */
  def releaseSavepoint(sp: Savepoint): ConnectionIO[Unit] =
    FC.releaseSavepoint(sp)

  /** @group Transaction Control */
  def rollback(sp: Savepoint): ConnectionIO[Unit] =
    FC.rollback(sp)

  /** @group Transaction Control */
  val rollback: ConnectionIO[Unit] =
    FC.rollback

  /** @group Connection Properties */
  def setCatalog(catalog: String): ConnectionIO[Unit] =
    FC.setCatalog(catalog)

  /** @group Connection Properties */
  def setClientInfo(key: String, value: String): ConnectionIO[Unit] =
    FC.setClientInfo(key, value)

  /** @group Connection Properties */
  def setClientInfo(info: Map[String, String]): ConnectionIO[Unit] =
    FC.setClientInfo {
      // Java 11 overloads the `putAll` method with Map[*,*] along with the existing Map[Obj,Obj]
      val ps = new java.util.Properties
      info.foreachEntry { case (k, v) =>
        ps.put(k, v)
      }
      ps
    }

  /** @group Connection Properties */
  def setHoldability(h: Holdability): ConnectionIO[Unit] =
    FC.setHoldability(h.toInt)

  /** @group Connection Properties */
  def setReadOnly(readOnly: Boolean): ConnectionIO[Unit] =
    FC.setReadOnly(readOnly)

  /** @group Transaction Control */
  val setSavepoint: ConnectionIO[Savepoint] =
    FC.setSavepoint

  /** @group Transaction Control */
  def setSavepoint(name: String): ConnectionIO[Savepoint] =
    FC.setSavepoint(name)

  /** @group Transaction Control */
  def setTransactionIsolation(ti: TransactionIsolation): ConnectionIO[Unit] =
    FC.setTransactionIsolation(ti.toInt)

  // /**
  //  * Compute a map from native type to closest-matching JDBC type.
  //  * @group MetaData
  //  */
  // val nativeTypeMap: ConnectionIO[Map[String, JdbcType]] = {
  //   getMetaData(FDMD.getTypeInfo.flatMap(FDMD.embed(_, HRS.list[(String, JdbcType)].map(_.toMap))))
  // }
}
