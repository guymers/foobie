// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

/**
 * Top-level import, providing aliases for the most commonly used types and
 * modules from doobie-free and doobie-core. A typical starting set of imports
 * would be something like this.
 * {{{
 * import cats.implicits.*
 * import doobie._, doobie.implicits.*
 * }}}
 * @see
 *   The [[http://tpolecat.github.io/doobie/ doobie microsite]] for much more
 *   information.
 */
package object doobie {

  type BlobIO[A] = doobie.free.blob.BlobIO[A]
  type CallableStatementIO[A] = doobie.free.callablestatement.CallableStatementIO[A]
  type ClobIO[A] = doobie.free.clob.ClobIO[A]
  type ConnectionIO[A] = doobie.free.connection.ConnectionIO[A]
  type DatabaseMetaDataIO[A] = doobie.free.databasemetadata.DatabaseMetaDataIO[A]
  type DriverIO[A] = doobie.free.driver.DriverIO[A]
  type NClobIO[A] = doobie.free.nclob.NClobIO[A]
  type PreparedStatementIO[A] = doobie.free.preparedstatement.PreparedStatementIO[A]
  type RefIO[A] = doobie.free.ref.RefIO[A]
  type ResultSetIO[A] = doobie.free.resultset.ResultSetIO[A]
  type SQLDataIO[A] = doobie.free.sqldata.SQLDataIO[A]
  type SQLInputIO[A] = doobie.free.sqlinput.SQLInputIO[A]
  type SQLOutputIO[A] = doobie.free.sqloutput.SQLOutputIO[A]
  type StatementIO[A] = doobie.free.statement.StatementIO[A]

  val FB = doobie.free.blob
  val FCS = doobie.free.callablestatement
  val FCL = doobie.free.clob
  val FC = doobie.free.connection
  val FDMD = doobie.free.databasemetadata
  val FD = doobie.free.driver
  val FNCL = doobie.free.nclob
  val FPS = doobie.free.preparedstatement
  val FREF = doobie.free.ref
  val FRS = doobie.free.resultset
  val FSD = doobie.free.sqldata
  val FSI = doobie.free.sqlinput
  val FSO = doobie.free.sqloutput
  val FS = doobie.free.statement

  val HC = doobie.hi.HC
  val HS = doobie.hi.HS
  val HPS = doobie.hi.HPS
  val HRS = doobie.hi.HRS

  type Meta[A] = doobie.util.meta.Meta[A]
  val Meta = doobie.util.meta.Meta

  type Get[A] = doobie.util.Get[A]
  val Get = doobie.util.Get

  type Put[A] = doobie.util.Put[A]
  val Put = doobie.util.Put

  type Read[A] = doobie.util.Read[A]
  val Read = doobie.util.Read

  type Write[A] = doobie.util.Write[A]
  val Write = doobie.util.Write

  type Query[A, B] = doobie.util.query.Query[A, B]
  val Query = doobie.util.query.Query

  type Update[A] = doobie.util.update.Update[A]
  val Update = doobie.util.update.Update

  type Query0[A] = doobie.util.query.Query0[A]
  val Query0 = doobie.util.query.Query0

  type Update0 = doobie.util.update.Update0
  val Update0 = doobie.util.update.Update0

  type SqlState = doobie.enumerated.SqlState
  val SqlState = doobie.enumerated.SqlState

  type Transactor[M[_]] = doobie.util.transactor.Transactor[M]
  val Transactor = doobie.util.transactor.Transactor

  type Fragment = doobie.util.fragment.Fragment
  val Fragment = doobie.util.fragment.Fragment

  type KleisliInterpreter[F[_]] = doobie.free.KleisliInterpreter[F]
  val KleisliInterpreter = doobie.free.KleisliInterpreter

  val Fragments = doobie.util.fragments

  val ExecutionContexts = doobie.util.ExecutionContexts

  object implicits extends syntax.AllSyntax

}
