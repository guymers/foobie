// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.Id
import cats.free.Free
import doobie.free.connection.ConnectionIO

import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource
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

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def run[T](c: Connection, strategy: Strategy)(io: ConnectionIO[T]): Id[T] = {
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
