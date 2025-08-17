package doobie.free.connection

import cats.Id
import cats.MonadError
import cats.data.Ior
import cats.data.NonEmptyList
import cats.free.Free
import cats.syntax.option.*
import cats.syntax.traverse.*
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
import scala.collection.Factory
import scala.collection.mutable.ListBuffer
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

  val unit: ConnectionIO[Unit] = liftF(Delay(() => ()))

  val commit: ConnectionIO[Unit] = liftF(Raw(_.commit()))
  val rollback: ConnectionIO[Unit] = liftF(Raw(_.rollback()))
  def setAutoCommit(autoCommit: Boolean): ConnectionIO[Unit] = liftF(Raw(_.setAutoCommit(autoCommit)))

  def raiseError[A](t: Throwable): ConnectionIO[A] = liftF(RaiseError(t))
  def handleErrorWith[A](fa: ConnectionIO[A])(f: Throwable => ConnectionIO[A]): ConnectionIO[A] = liftF(HandleErrorWith(fa, f))

  def collect[F[_], A, B](sql: String, a: A)(implicit read: Read[B], write: Write[A], factory: Factory[B, F[B]]): ConnectionIO[F[B]] = {
    def f(statement: PreparedStatement) = {
      write.unsafeSet(statement, 1, a)
      Using.resource(statement.executeQuery()) { rs =>
        val b = factory.newBuilder
        while (rs.next) {
          val _ = b += read.unsafeGet(rs, 1)
        }
        b.result()
      }
    }
    liftF[ConnectionOp, F[B]](WithPreparedStatement(sql, f))
  }

  def collectPair[F[_], A, K, V](sql: String, a: A)(implicit
    read: Read[(K, V)],
    write: Write[A],
    factory: Factory[(K, V), Map[K, V]],
  ): ConnectionIO[Map[K, V]] = {
    def f(statement: PreparedStatement) = {
      write.unsafeSet(statement, 1, a)
      Using.resource(statement.executeQuery()) { rs =>
        val b = factory.newBuilder
        while (rs.next) {
          val _ = b += read.unsafeGet(rs, 1)
        }
        b.result()
      }
    }
    liftF[ConnectionOp, Map[K, V]](WithPreparedStatement(sql, f))
  }

  def unique[A, B](sql: String, a: A)(implicit read: Read[B], write: Write[A]): ConnectionIO[B] = {
    def f(statement: PreparedStatement) = {
      write.unsafeSet(statement, 1, a)
      Using.resource(statement.executeQuery()) { rs =>
        if (!rs.next()) {
          throw UnexpectedEnd()
        }
        val r = read.unsafeGet(rs, 1)
        if (rs.next()) {
          throw UnexpectedContinuation()
        }
        r
      }
    }
    liftF[ConnectionOp, B](WithPreparedStatement(sql, f))
  }

  def option[A, B](sql: String, a: A)(implicit read: Read[B], write: Write[A]): ConnectionIO[Option[B]] = {
    def f(statement: PreparedStatement) = {
      write.unsafeSet(statement, 1, a)
      Using.resource(statement.executeQuery()) { rs =>
        val r = Option.when(rs.next())(read.unsafeGet(rs, 1))
        if (rs.next()) {
          throw UnexpectedContinuation()
        }
        r
      }
    }
    liftF[ConnectionOp, Option[B]](WithPreparedStatement(sql, f))
  }

  def nel[A, B](sql: String, a: A)(implicit read: Read[B], write: Write[A]): ConnectionIO[NonEmptyList[B]] = {
    def f(statement: PreparedStatement) = {
      write.unsafeSet(statement, 1, a)
      Using.resource(statement.executeQuery()) { rs =>
        if (!rs.next()) {
          throw UnexpectedEnd()
        }
        val head = read.unsafeGet(rs, 1)
        val tail = {
          val b = List.newBuilder[B]
          while (rs.next) {
            val _ = b += read.unsafeGet(rs, 1)
          }
          b.result()
        }
        NonEmptyList(head, tail)
      }
    }
    liftF[ConnectionOp, NonEmptyList[B]](WithPreparedStatement(sql, f))
  }

  def prepareQueryAnalysis[A, B](sql: String)(implicit write: Write[A], read: Read[B]): ConnectionIO[Analysis] =
    prepareAnalysis(sql, write.some, read.some)

  def prepareQueryAnalysis0[B](sql: String)(implicit read: Read[B]): ConnectionIO[Analysis] =
    prepareAnalysis(sql, None, read.some)

  def prepareUpdateAnalysis[A](sql: String)(implicit write: Write[A]): ConnectionIO[Analysis] =
    prepareAnalysis(sql, write.some, None)

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

  implicit val MonadErrorConnectionIO: MonadError[ConnectionIO, Throwable] =
    new MonadError[ConnectionIO, Throwable] {
      override def pure[A](x: A) = monad.pure(x)
      override def map[A, B](fa: ConnectionIO[A])(f: A => B) = monad.map(fa)(f)
      override def flatMap[A, B](fa: ConnectionIO[A])(f: A => ConnectionIO[B]) = monad.flatMap(fa)(f)
      override def tailRecM[A, B](a: A)(f: A => ConnectionIO[Either[A, B]]) = monad.tailRecM(a)(f)
      override def raiseError[A](e: Throwable) = self.raiseError(e)
      override def handleErrorWith[A](fa: ConnectionIO[A])(f: Throwable => ConnectionIO[A]) = self.handleErrorWith(fa)(f)
    }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def interpreter(conn: Connection): ConnectionOp ~> Id = new (ConnectionOp ~> Id) {

    def apply[A](fa: ConnectionOp[A]): Id[A] = fa match {
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

      case WithPreparedStatement(sql, f) =>
        Using.resource(conn.prepareStatement(sql)) { stmt =>
          stmt.setFetchSize(1024)
          f(stmt)
        }
    }
  }
}
