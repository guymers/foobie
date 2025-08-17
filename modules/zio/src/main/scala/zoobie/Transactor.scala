package zoobie

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
import zio.interop.catz.core.*

import java.sql.Connection

sealed abstract class Transactor { self =>

  def connection: ZIO[Scope, DatabaseError.Connection, Connection]

  def strategy: Strategy

  /**
   * Execute the given [[ConnectionIO]] on a connection using the strategy.
   */
  def run[A](io: ConnectionIO[A])(implicit trace: Trace): ZIO[Any, DatabaseError, A] = ZIO.scoped[Any] {
    for {
      conn <- connection
      interpret = Free.foldMap(Transactor.interpreter(conn))
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
    override val strategy = s
  }

}

object Transactor {

  object strategies {
    val noop: Strategy = Strategy.void
    val transactional: Strategy = Strategy.default
    val rollback: Strategy = transactional.copy(after = doobie.free.connection.ConnectionIO.rollback)
  }

  def interpreter(conn: Connection): ConnectionOp ~> Task = new (ConnectionOp ~> Task) {
    import ConnectionOp.*
    override def apply[A](fa: ConnectionOp[A]) = fa match {
      case Raw(f) => ZIO.attemptBlocking(f(conn))
      case Delay(f) => ZIO.succeed(f())
      case RaiseError(t) => ZIO.fail(t)
      case HandleErrorWith(fa, f) =>
        val run = Free.foldMap(this)
        run(fa).catchAll(t => run(f(t)))

      case WithPreparedStatement(create, f) =>
        ZIO.scoped {
          for {
            stmt <- ZIO.acquireRelease(
              ZIO.attemptBlocking(create(conn)),
            )(stmt => ZIO.attemptBlocking(stmt.close()).ignoreLogged)
            result <- ZIO.attemptBlockingCancelable({
              stmt.setFetchSize(1024)
              f(stmt)
            })(ZIO.attemptBlocking(stmt.cancel()).ignoreLogged)
          } yield result
        }
    }
  }

  def apply(
    connection0: ZIO[Scope, DatabaseError.Connection, Connection],
    strategy0: Strategy,
  ): Transactor = new Transactor {
    override val connection = connection0
    override val strategy = strategy0
  }

  def fromPool(pool: ConnectionPool, strategy: Strategy): Transactor = {
    apply(pool.get, strategy)
  }

  def fromPoolTransactional(pool: ConnectionPool): Transactor = {
    fromPool(pool, strategies.transactional)
  }
}
