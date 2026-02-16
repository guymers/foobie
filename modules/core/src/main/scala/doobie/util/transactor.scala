// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.Id
import cats.free.Free
import doobie.free.connection.ConnectionIO

import java.sql.Connection
import scala.util.Using
import scala.util.control.NonFatal

object transactor {

  /**
   * Data type representing the common setup, error-handling, and cleanup
   * strategy associated with an SQL transaction. A [[Transactor]] uses a
   * [[Strategy]] to wrap programs prior to execution.
   * @param before
   *   a program to prepare the connection for use
   * @param after
   *   a program to run on success
   * @param oops
   *   a program to run on failure (catch)
   * @param always
   *   a program to run in all cases (finally)
   * @group Data Types
   */
  final case class Strategy(
    before: ConnectionIO[Unit],
    after: ConnectionIO[Unit],
    oops: ConnectionIO[Unit],
    always: ConnectionIO[Unit],
  )
  object Strategy {
    import ConnectionIO.*

    /**
     * A default `Strategy` with the following properties:
     *   - Auto-commit will be set to `false`;
     *   - the transaction will `commit` on success and `rollback` on failure;
     * @group Constructors
     */
    val default = Strategy(setAutoCommit(false), commit, rollback, unit)

    /**
     * A no-op `Strategy`. All actions simply return `()`.
     * @group Constructors
     */
    val void = Strategy(unit, unit, unit, unit)

  }

  /**
   * A thin wrapper around a source of database connections, an interpreter, and
   * a strategy for running programs, parameterized over a target monad `M` and
   * an arbitrary wrapped value `A`. Given a stream or program in `ConnectionIO`
   * or a program in `Kleisli`, a `Transactor` can discharge the doobie
   * machinery and yield an effectful stream or program in `M`.
   * @tparam M
   *   a target effect type; typically `IO`
   * @group Data Types
   */
  sealed abstract class Transactor[M[_]] { self =>

    /** An arbitrary value that will be handed back to `connect` * */
    type A

    /** An arbitrary value, meaningful to the instance * */
    def kernel: A

    /** A `Strategy` for running a program on a connection * */
    def strategy: Strategy

    /**
     * Construct a program to perform arbitrary configuration on the kernel
     * value (changing the timeout on a connection pool, for example). This can
     * be the basis for constructing a configuration language for a specific
     * kernel type `A`, whose operations can be added to compatible
     * `Transactor`s via implicit conversion.
     * @group Configuration
     */
    def configure[B](f: A => M[B]): M[B] =
      f(kernel)

    def run[T](io: ConnectionIO[T]): M[T]
  }

  object Transactor {

    type Aux[M[_], A0] = Transactor[M] { type A = A0 }

    def id(
      connect: () => Connection,
      strategy0: Strategy = Strategy.default,
    ) = new Transactor[Id] {
      override type A = Unit
      override val kernel = ()
      override val strategy = strategy0

      override def run[T](io: ConnectionIO[T]) = {
        Using.resource(connect())(runWithConnection(_)(io))
      }

      @SuppressWarnings(Array("org.wartremover.warts.Throw"))
      private def runWithConnection[T](c: Connection)(io: ConnectionIO[T]): Id[T] = {
        val interpret = Free.foldMap(ConnectionIO.interpreter(c))
        try {
          interpret(strategy.before)
          val result = interpret(io)
          interpret(strategy.after)
          result
        } catch {
          case NonFatal(t) =>
            interpret(strategy.oops)
            throw t
        } finally {
          interpret(strategy.always)
        }
      }
    }

    def catsEffect[M[_], A0](
      kernel0: A0,
      connect: cats.effect.kernel.Resource[M, Connection],
      strategy0: Strategy = Strategy.default,
    )(implicit S: cats.effect.kernel.Sync[M]): Transactor.Aux[M, A0] = new Transactor[M] {
      override type A = A0
      override val kernel = kernel0
      override val strategy = strategy0

      override def run[T](io: ConnectionIO[T]) = {
        connect.use { conn =>
          val interpret = Free.foldMap(ConnectionIO.interpreterCatsEffect(conn))
          (for {
            _ <- cats.effect.kernel.Resource.make(S.unit)(_ => interpret(strategy.always))
            _ <- cats.effect.kernel.Resource.makeCase(interpret(strategy.before)) { case (_, exitCase) =>
              exitCase match {
                case cats.effect.kernel.Resource.ExitCase.Succeeded => interpret(strategy.after)
                case cats.effect.kernel.Resource.ExitCase.Errored(_) | cats.effect.kernel.Resource.ExitCase.Canceled => interpret(strategy.oops)
              }
            }
          } yield ()).use(_ => interpret(io))
        }
      }
    }
  }
}
