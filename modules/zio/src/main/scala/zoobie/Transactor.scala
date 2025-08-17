package zoobie

import cats.Monad
import cats.free.Free
import cats.~>
import doobie.free.connection.ConnectionIO
import doobie.free.connection.ConnectionOp
import doobie.util.transactor.Strategy
import zio.Exit
import zio.Scope
import zio.Task
import zio.Trace
import zio.ZIO

import java.sql.Connection

sealed abstract class Transactor { self =>
  import Transactor.monadTask

  def connection: ZIO[Scope, DatabaseError.Connection, Connection]

  def interpreter: Connection => ConnectionOp ~> Task

  def interpret(conn: Connection): ConnectionIO ~> Task = Free.foldMap(self.interpreter(conn))

  def strategy: Strategy

  /**
   * Execute the given [[ConnectionIO]] on a connection using the strategy.
   */
  def run[A](io: ConnectionIO[A])(implicit trace: Trace): ZIO[Any, DatabaseError, A] = ZIO.scoped[Any] {
    for {
      conn <- connection
      interpret = Free.foldMap(interpreter(conn))
      _ <- ZIO.acquireRelease(Exit.unit)(_ => interpret(strategy.always).ignoreLogged)
      _ <- ZIO.acquireReleaseExit(interpret(strategy.before)) {
        case (_, Exit.Success(_)) => interpret(strategy.after).ignoreLogged
        case (_, Exit.Failure(_)) => interpret(strategy.oops).ignoreLogged
      }
      result <- interpret(io)
    } yield result
  }.mapError(DatabaseError(_))

  def withStrategy(s: Strategy): Transactor = new Transactor {
    override val connection = self.connection
    override val interpreter = self.interpreter
    override val strategy = s
  }

}

object Transactor {

  object strategies {
    val noop: Strategy = Strategy.void
    val transactional: Strategy = Strategy.default
    val rollback: Strategy = transactional.copy(after = doobie.free.connection.ConnectionIO.rollback)
  }

  implicit val monadTask: Monad[Task] = zio.interop.catz.monadErrorInstance

  def interpreter(conn: Connection, fetchSize: Int = 0): ConnectionOp ~> Task = new (ConnectionOp ~> Task) {
    import ConnectionOp.*
    override def apply[A](fa: ConnectionOp[A]) = fa match {
      case Raw(f) => ZIO.attemptBlocking(f(conn))
      case Delay(f) => ZIO.succeed(f())
      case RaiseError(t) => ZIO.fail(t)
      case HandleErrorWith(fa, f) =>
        val run = Free.foldMap(this)
        run(fa).catchAll(t => run(f(t)))

      case WithPreparedStatement(sql, create, f) =>
        ZIO.scoped {
          for {
            stmt <- ZIO.acquireRelease(
              ZIO.attemptBlocking(create(conn, sql)),
            )(stmt => ZIO.attemptBlocking(stmt.close()).ignoreLogged)
            result <- ZIO.attemptBlockingCancelable({
              if (fetchSize > 0) {
                stmt.setFetchSize(fetchSize)
              }
              f(stmt)
            })(ZIO.attemptBlocking(stmt.cancel()).ignoreLogged)
          } yield result
        }
    }
  }

  def apply(
    connection0: ZIO[Scope, DatabaseError.Connection, Connection],
    interpreter0: Connection => ConnectionOp ~> Task,
    strategy0: Strategy,
  ): Transactor = new Transactor {
    override val connection = connection0
    override val interpreter = interpreter0
    override val strategy = strategy0
  }

  def fromPool(pool: ConnectionPool, strategy: Strategy): Transactor = {
    apply(pool.get, interpreter(_), strategy)
  }

  def fromPoolTransactional(pool: ConnectionPool): Transactor = {
    fromPool(pool, strategies.transactional)
  }
}
