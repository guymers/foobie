package zoobie

import cats.Monad
import cats.effect.kernel.Sync
import cats.~>
import doobie.free.KleisliInterpreter
import doobie.free.connection.ConnectionIO
import doobie.util.transactor.Interpreter
import doobie.util.transactor.Strategy
import fs2.Stream
import zio.Scope
import zio.Task
import zio.Trace
import zio.ZIO
import zio.stream.ZStream

import java.sql.Connection

sealed abstract class Transactor { self =>

  private val chunkSize = doobie.util.query.DefaultChunkSize

  def connection: ZIO[Scope, DatabaseError.Connection, Connection]

  def interpreter: Interpreter[Task]

  def strategy: Strategy

  /**
   * Execute the given [[ConnectionIO]] on a connection using the strategy.
   */
  def run[A](io: ConnectionIO[A])(implicit trace: Trace): ZIO[Any, DatabaseError, A] = ZIO.scoped {
    connection.flatMap(interpret(io)(_))
  }

  /**
   * Executes each [[ConnectionIO]] in the stream individually on a connection
   * using the strategy.
   */
  def stream[A](s: Stream[ConnectionIO, A])(implicit trace: Trace): ZStream[Any, DatabaseError, A] = {
    import zio.stream.interop.fs2z.*

    s.translate(new (ConnectionIO ~> Task) {
      override def apply[T](io: ConnectionIO[T]) = run(io)
    }).toZStream(chunkSize).mapError(DatabaseError(_))
  }

  /**
   * Executes each [[ConnectionIO]] in the stream on a single connection using
   * the strategy.
   *
   * Use with care with a strategy that is transactional as the transaction will
   * be open until the stream completes. This means if the connection came from
   * a pool it will not be returned until the stream completes.
   */
  def streamSingleConnection[A](s: Stream[ConnectionIO, A])(implicit trace: Trace): ZStream[Any, DatabaseError, A] = {
    import zio.stream.interop.fs2z.*

    ZStream.scoped[Any](connection).flatMap { conn =>
      Stream.resource(strategy.resource).flatMap(_ => s)
        .translate(translate(conn)).toZStream(chunkSize).mapError(DatabaseError(_))
    }
  }

  def interpret[A](io: ConnectionIO[A])(c: Connection): ZIO[Any, DatabaseError, A] = {
    translate(c) { strategy.resource.use(_ => io) }
      .mapError(DatabaseError(_))
  }

  def translate(c: Connection): ConnectionIO ~> Task = {
    implicit val monad: Monad[Task] = Transactor.sync

    new (ConnectionIO ~> Task) {
      override def apply[T](io: ConnectionIO[T]) = io.foldMap(interpreter).run(c)
    }
  }

  def withStrategy(s: Strategy): Transactor = new Transactor {
    override val connection = self.connection
    override val interpreter = self.interpreter
    override val strategy = s
  }

}

object Transactor {

  private val sync: Sync[Task] = zio.interop.catz.asyncInstance[Any]

  val interpreter: Interpreter[Task] = KleisliInterpreter(sync).ConnectionInterpreter

  object strategies {
    val noop: Strategy = Strategy.void
    val transactional: Strategy = Strategy.default
    val rollback: Strategy = Strategy.after.set(transactional, doobie.free.connection.rollback)
  }

  def apply(
    connection0: ZIO[Scope, DatabaseError.Connection, Connection],
    interpreter0: Interpreter[Task],
    strategy0: Strategy,
  ): Transactor = new Transactor {
    override val connection = connection0
    override val interpreter = interpreter0
    override val strategy = strategy0
  }

  def fromPool(pool: ConnectionPool, strategy: Strategy): Transactor = {
    apply(pool.get, interpreter, strategy)
  }

  def fromPoolTransactional(pool: ConnectionPool): Transactor = {
    fromPool(pool, strategies.transactional)
  }

}
