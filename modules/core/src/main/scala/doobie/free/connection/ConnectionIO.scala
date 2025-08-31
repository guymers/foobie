package doobie.free.connection

import cats.Foldable
import cats.Id
import cats.MonadError
import cats.Monoid
import cats.data.Ior
import cats.data.NonEmptyList
import cats.free.Free
import cats.syntax.foldable.*
import cats.syntax.option.*
import cats.~>
import doobie.enumerated.ColumnNullable
import doobie.enumerated.JdbcType
import doobie.enumerated.Nullability
import doobie.enumerated.ParameterMode
import doobie.enumerated.ParameterNullable
import doobie.syntax.align.*
import doobie.util.Get
import doobie.util.Put
import doobie.util.Read
import doobie.util.Write
import doobie.util.analysis.Analysis
import doobie.util.analysis.ColumnMeta
import doobie.util.analysis.ParameterMeta
import doobie.util.invariant.InvalidOrdinal
import doobie.util.invariant.UnexpectedContinuation
import doobie.util.invariant.UnexpectedEnd

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import scala.collection.Factory
import scala.util.Using
import scala.util.control.NonFatal

sealed abstract class ConnectionOp[T]
object ConnectionOp {

  final case class Raw[A](f: Connection => A) extends ConnectionOp[A]

  final case class Delay[A](v: () => A) extends ConnectionOp[A]

  final case class RaiseError[A](t: Throwable) extends ConnectionOp[A]
  final case class HandleErrorWith[A](fa: ConnectionIO[A], f: Throwable => ConnectionIO[A]) extends ConnectionOp[A]

  final case class WithPreparedStatement[A](
    sql: String,
    create: (Connection, String) => PreparedStatement,
    f: PreparedStatement => A,
  ) extends ConnectionOp[A]
}

@SuppressWarnings(Array(
  "org.wartremover.warts.MutableDataStructures",
  "org.wartremover.warts.Throw",
  "org.wartremover.warts.TryPartial",
  "org.wartremover.warts.While",
))
object ConnectionIO { self =>
  import ConnectionOp.*
  import Free.liftF

  def delay[A](a: => A): ConnectionIO[A] = liftF(Delay(() => a))
  val unit: ConnectionIO[Unit] = delay(())

  val commit: ConnectionIO[Unit] = liftF(Raw(_.commit()))
  val rollback: ConnectionIO[Unit] = liftF(Raw(_.rollback()))
  def setAutoCommit(autoCommit: Boolean): ConnectionIO[Unit] = liftF(Raw(_.setAutoCommit(autoCommit)))

  def raiseError[A](t: Throwable): ConnectionIO[A] = liftF(RaiseError(t))
  def handleErrorWith[A](fa: ConnectionIO[A])(f: Throwable => ConnectionIO[A]): ConnectionIO[A] = liftF(HandleErrorWith(fa, f))

  object query {

    def iterator[A, B, C](sql: String, a: A, f: Iterator[B] => C)(implicit R: Read[B], W: Write[A]): ConnectionIO[C] = {
      def resultSet(rs: ResultSet) = {
        val iterator = new Iterator[B] {
          override def hasNext: Boolean = rs.next()
          override def next(): B = R.unsafeGet(rs, 1)
        }
        f(iterator)
      }
      withPreparedStatementExecuteQuery(sql, a, resultSet)
    }

    def collect[F[_], A, B](sql: String, a: A)(implicit R: Read[B], W: Write[A], factory: Factory[B, F[B]]): ConnectionIO[F[B]] = {
      withPreparedStatementExecuteQuery(sql, a, resultSetCollect[F, B])
    }

    private[ConnectionIO] def resultSetCollect[F[_], B](rs: ResultSet)(implicit R: Read[B], factory: Factory[B, F[B]]) = {
      val b = factory.newBuilder
      while (rs.next) {
        val _ = b += R.unsafeGet(rs, 1)
      }
      b.result()
    }

    def collectPair[A, K, V](sql: String, a: A)(implicit
      R: Read[(K, V)],
      W: Write[A],
      factory: Factory[(K, V), Map[K, V]],
    ): ConnectionIO[Map[K, V]] = {
      def resultSet(rs: ResultSet) = {
        val b = factory.newBuilder
        while (rs.next) {
          val _ = b += R.unsafeGet(rs, 1)
        }
        b.result()
      }
      withPreparedStatementExecuteQuery(sql, a, resultSet)
    }

    def unique[A, B](sql: String, a: A)(implicit R: Read[B], W: Write[A]): ConnectionIO[B] = {
      withPreparedStatementExecuteQuery(sql, a, resultSetUnique[B])
    }

    private[ConnectionIO] def resultSetUnique[B](rs: ResultSet)(implicit R: Read[B]) = {
      if (!rs.next()) {
        throw UnexpectedEnd()
      }
      val r = R.unsafeGet(rs, 1)
      if (rs.next()) {
        throw UnexpectedContinuation()
      }
      r
    }

    def option[A, B](sql: String, a: A)(implicit R: Read[B], W: Write[A]): ConnectionIO[Option[B]] = {
      def resultSet(rs: ResultSet) = {
        val r = Option.when(rs.next())(R.unsafeGet(rs, 1))
        if (rs.next()) {
          throw UnexpectedContinuation()
        }
        r
      }
      withPreparedStatementExecuteQuery(sql, a, resultSet)
    }

    def nel[A, B](sql: String, a: A)(implicit R: Read[B], W: Write[A]): ConnectionIO[NonEmptyList[B]] = {
      def resultSet(rs: ResultSet) = {
        if (!rs.next()) {
          throw UnexpectedEnd()
        }
        val head = R.unsafeGet(rs, 1)
        val tail = {
          val b = List.newBuilder[B]
          while (rs.next) {
            val _ = b += R.unsafeGet(rs, 1)
          }
          b.result()
        }
        NonEmptyList(head, tail)
      }
      withPreparedStatementExecuteQuery(sql, a, resultSet)
    }

    private def withPreparedStatementExecuteQuery[A, B](sql: String, a: A, f: ResultSet => B)(implicit W: Write[A]) = {
      withPreparedStatement(
        sql,
        _.prepareStatement(_),
        statement => {
          W.unsafeSet(statement, 1, a)
          Using.resource(statement.executeQuery())(f)
        },
      )
    }
  }

  object update {

    def run[A](sql: String, a: A)(implicit W: Write[A]): ConnectionIO[Int] = {
      def statement(ps: PreparedStatement) = {
        W.unsafeSet(ps, 1, a)
        ps.executeUpdate()
      }
      withPreparedStatement(sql, _.prepareStatement(_), statement)
    }

    def many[F[_]: Foldable, A](sql: String, fa: F[A])(implicit W: Write[A]): ConnectionIO[Int] = {
      def statement(ps: PreparedStatement) = {
        if (fa.isEmpty) {
          0
        } else {
          fa.toIterable.foreach { a =>
            W.unsafeSet(ps, 1, a)
            ps.addBatch()
          }
          ps.executeBatch().foldLeft(0)((acc, n) => acc + n.max(0)) // treat negatives (failures) as no rows updated
        }
      }
      withPreparedStatement(sql, _.prepareStatement(_), statement)
    }

    def manyReturningGeneratedKeys[F[_]: Foldable, A, B](
      sql: String,
      fa: F[A],
      columns: List[String],
    )(implicit W: Write[A], R: Read[B], F: Factory[B, F[B]]): ConnectionIO[F[B]] = {
      def statement(ps: PreparedStatement) = {
        if (fa.isEmpty) {
          F.newBuilder.result()
        } else {
          fa.toIterable.foreach { a =>
            W.unsafeSet(ps, 1, a)
            ps.addBatch()
          }
          val _ = ps.executeBatch()
          Using.resource(ps.getGeneratedKeys)(query.resultSetCollect[F, B])
        }
      }
      withPreparedStatement(sql, _.prepareStatement(_, columns.toArray), statement)
    }

    def generatedKeysUnique[A, B](sql: String, a: A, columns: List[String])(implicit W: Write[A], R: Read[B]): ConnectionIO[B] = {
      def statement(ps: PreparedStatement) = {
        W.unsafeSet(ps, 1, a)
        val _ = ps.executeUpdate()
        Using.resource(ps.getGeneratedKeys)(query.resultSetUnique[B])
      }
      withPreparedStatement(sql, _.prepareStatement(_, columns.toArray), statement)
    }
  }

  private def withPreparedStatement[A](
    sql: String,
    create: (Connection, String) => PreparedStatement,
    f: PreparedStatement => A,
  ): ConnectionIO[A] = {
    liftF[ConnectionOp, A](WithPreparedStatement(sql, create, f))
  }

  def prepareQueryAnalysis[A, B](sql: String)(implicit W: Write[A], R: Read[B]): ConnectionIO[Analysis] =
    prepareAnalysis(sql, W.some, R.some)

  def prepareQueryAnalysis0[B](sql: String)(implicit R: Read[B]): ConnectionIO[Analysis] =
    prepareAnalysis(sql, None, R.some)

  def prepareUpdateAnalysis[A](sql: String)(implicit W: Write[A]): ConnectionIO[Analysis] =
    prepareAnalysis(sql, W.some, None)

  def prepareUpdateAnalysis0(sql: String): ConnectionIO[Analysis] =
    prepareAnalysis(sql, None, None)

  type PrepareAnalysisMeta = (
    List[Ior[(Put[?], Nullability.NullabilityKnown), ParameterMeta]],
    List[Ior[(Get[?], Nullability.NullabilityKnown), ColumnMeta]],
  )

  private def prepareAnalysis[A, B](
    sql: String,
    write: Option[Write[A]],
    read: Option[Read[B]],
  ): ConnectionIO[Analysis] = for {
    dbMetaData <- liftF[ConnectionOp, DatabaseMetaData](Raw(_.getMetaData()))
    driver = dbMetaData.getDriverName
    tuple <- liftF[ConnectionOp, PrepareAnalysisMeta](WithPreparedStatement(
      sql,
      (c, s) => c.prepareStatement(s),
      s => (parameterMetadata(write, s), columnMeta(read, s)),
    ))
    (p, c) = tuple
  } yield {
    Analysis(driver, sql, p, c)
  }

  private def columnMeta(read: Option[Read[?]], statement: PreparedStatement) = (for {
    r <- read
    md <- Option(statement.getMetaData)
  } yield {
    val m = (1 to md.getColumnCount).toList.map { i =>
      val n = ColumnNullable.fromInt(md.isNullable(i)).getOrElse {
        throw InvalidOrdinal[ColumnNullable](i)
      }
      val j = JdbcType.fromInt(md.getColumnType(i))
      val s = md.getColumnTypeName(i)
      val c = md.getColumnName(i)
      ColumnMeta(j, s, n.toNullability, c)
    }
    r.gets.toList.align(m)
  }).orEmpty

  private def parameterMetadata(write: Option[Write[?]], statement: PreparedStatement) = (for {
    w <- write
    md <- Option(statement.getParameterMetaData)
  } yield {
    val m = (1 to md.getParameterCount).toList.map { i =>
      val n = ParameterNullable.fromInt(md.isNullable(i)).getOrElse {
        throw InvalidOrdinal[ParameterNullable](i)
      }
      val m = ParameterMode.fromInt(md.getParameterMode(i)).getOrElse {
        throw InvalidOrdinal[ParameterMode](i)
      }
      val j = JdbcType.fromInt(md.getParameterType(i))
      val s = md.getParameterTypeName(i)
      ParameterMeta(j, s, n.toNullability, m)
    }
    w.puts.toList.align(m)
  }).orEmpty

  private val monad = Free.catsFreeMonadForFree[ConnectionOp]

  implicit val MonadErrorConnectionIO: MonadError[ConnectionIO, Throwable] = new MonadError[ConnectionIO, Throwable] {
    override def pure[A](x: A) = monad.pure(x)
    override def map[A, B](fa: ConnectionIO[A])(f: A => B) = monad.map(fa)(f)
    override def flatMap[A, B](fa: ConnectionIO[A])(f: A => ConnectionIO[B]) = monad.flatMap(fa)(f)
    override def tailRecM[A, B](a: A)(f: A => ConnectionIO[Either[A, B]]) = monad.tailRecM(a)(f)
    override def raiseError[A](e: Throwable) = self.raiseError[A](e)
    override def handleErrorWith[A](fa: ConnectionIO[A])(f: Throwable => ConnectionIO[A]) = self.handleErrorWith(fa)(f)
  }

  implicit def MonoidConnectionIO[A](implicit M: Monoid[A]): Monoid[ConnectionIO[A]] =
    new Monoid[ConnectionIO[A]] {
      override val empty = monad.pure(M.empty)
      override def combine(x: ConnectionIO[A], y: ConnectionIO[A]) =
        monad.product(x, y).map { case (x, y) => M.combine(x, y) }
    }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def interpreter(conn: Connection): ConnectionOp ~> Id = new (ConnectionOp ~> Id) {
    override def apply[A](fa: ConnectionOp[A]) = fa match {
      case Raw(f) => f(conn)
      case Delay(f) => f()
      case RaiseError(t) => throw t
      case HandleErrorWith(fa, f) =>
        val run = Free.foldMap(this)
        try {
          run(fa)
        } catch {
          case NonFatal(t) => run(f(t))
        }

      case WithPreparedStatement(sql, create, f) =>
        Using.resource(create(conn, sql)) { stmt =>
          stmt.setFetchSize(1024)
          f(stmt)
        }
    }
  }

  def interpreterCatsEffect[M[_]](conn: Connection)(implicit M: cats.effect.kernel.Sync[M]): ConnectionOp ~> M = new (ConnectionOp ~> M) {
    override def apply[A](fa: ConnectionOp[A]) = fa match {
      case Raw(f) => M.blocking(f(conn))
      case Delay(f) => M.delay(f())
      case RaiseError(t) => M.raiseError(t)
      case HandleErrorWith(fa, f) =>
        val run = Free.foldMap(this)
        M.handleErrorWith(run(fa))(t => run(f(t)))

      case WithPreparedStatement(sql, create, f) =>
        cats.effect.kernel.Resource.fromAutoCloseable(M.blocking(create(conn, sql))).use { stmt =>
          M.blocking {
            stmt.setFetchSize(1024)
            f(stmt)
          }
        }
    }
  }
}
