// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.ApplicativeError
import cats.syntax.either.*
import doobie.enumerated.SqlState
import zio.Ref
import zio.Task
import zio.ZIO
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.sql.SQLException

object ApplicativeErrorSyntaxSuite extends ZIOSpecDefault {
  import doobie.syntax.applicativeerror.*

  private val SQLSTATE_FOO = SqlState("Foo")
  private val SQLSTATE_BAR = SqlState("Bar")

  private def success[A](a: A): Either[Throwable, A] = Right(a)
  private def failure[A](t: Throwable): Either[Throwable, A] = Left(t)

  override val spec = suite("ApplicativeErrorSyntax")(
    suite("attemptSql")(
      test("do nothing on success") {
        assertTrue(success(3).attemptSql == Right(3).asRight)
      },
      test("catch SQLException") {
        val e = new SQLException
        assertTrue(failure(e).attemptSql == Left(e).asRight)
      },
      test("ignore non-SQLException") {
        val e = new IllegalArgumentException
        assertTrue(failure(e).attemptSql == Left(e))
      },
    ),
    suite("attemptSqlState")(
      test("do nothing on success") {
        assertTrue(success(3).attemptSqlState == Right(3).asRight)
      },
      test("catch SQLException") {
        val e = new SQLException("", SQLSTATE_FOO.value)
        assertTrue(failure(e).attemptSqlState == Left(SQLSTATE_FOO).asRight)
      },
      test("ignore non-SQLException") {
        val e = new IllegalArgumentException
        assertTrue(failure(e).attemptSqlState == Left(e))
      },
    ),
    suite("attemptSomeSqlState")(
      test("do nothing on success") {
        val result = success(3).attemptSomeSqlState {
          case SQLSTATE_FOO => 42
          case SQLSTATE_BAR => 66
        }
        assertTrue(result == Right(3).asRight)
      },
      test("catch SQLException with matching state (1)") {
        val e = new SQLException("", SQLSTATE_FOO.value)
        val result = failure(e).attemptSomeSqlState {
          case SQLSTATE_FOO => 42
          case SQLSTATE_BAR => 66
        }
        assertTrue(result == Left(42).asRight)
      },
      test("catch SQLException with matching state (2)") {
        val e = new SQLException("", SQLSTATE_BAR.value)
        val result = failure(e).attemptSomeSqlState {
          case SQLSTATE_FOO => 42
          case SQLSTATE_BAR => 66
        }
        assertTrue(result == Left(66).asRight)
      },
      test("ignore SQLException with non-matching state") {
        val e = new SQLException("", SQLSTATE_BAR.value)
        val result = failure(e).attemptSomeSqlState {
          case SQLSTATE_FOO => 42
        }
        assertTrue(result == Left(e))
      },
      test("ignore non-SQLException") {
        val e = new IllegalArgumentException
        val result = failure(e).attemptSomeSqlState {
          case SQLSTATE_FOO => 42
        }
        assertTrue(result == Left(e))
      },
    ),
    suite("exceptSql")(
      test("do nothing on success") {
        assertTrue(success(3).exceptSql(_ => success(4)) == Right(3))
      },
      test("catch SQLException") {
        val e = new SQLException()
        assertTrue(failure(e).exceptSql(_ => success(4)) == Right(4))
      },
      test("ignore non-SQLException") {
        val e = new IllegalArgumentException
        assertTrue(failure(e).exceptSql(_ => success(4)) == Left(e))
      },
    ),
    suite("exceptSqlState")(
      test("do nothing on success") {
        assertTrue(success(3).exceptSqlState(_ => success(4)) == Right(3))
      },
      test("catch SQLException") {
        val e = new SQLException("", SQLSTATE_FOO.value)
        assertTrue(failure(e).exceptSqlState(_ => success(4)) == Right(4))
      },
      test("ignore non-SQLException") {
        val e = new IllegalArgumentException
        assertTrue(failure(e).exceptSqlState(_ => success(4)) == Left(e))
      },
    ),
    suite("exceptSomeSqlState")(
      test("do nothing on success") {
        assertTrue(success(3).exceptSomeSqlState { case _ => success(4) } == Right(3))
      },
      test("catch SQLException with some state") {
        val e = new SQLException("", SQLSTATE_FOO.value)
        assertTrue(failure(e).exceptSomeSqlState { case SQLSTATE_FOO => success(4) } == Right(4))
      },
      test("ignore SQLException with other state") {
        val e = new SQLException("", SQLSTATE_FOO.value)
        assertTrue(failure(e).exceptSomeSqlState { case SQLSTATE_BAR => success(4) } == Left(e))
      },
      test("ignore non-SQLException") {
        val e = new IllegalArgumentException
        assertTrue(failure(e).exceptSomeSqlState { case _ => success(4) } == Left(e))
      },
    ),
    suite("onSqlException")({
      implicit val zioAE: ApplicativeError[Task, Throwable] = zio.interop.catz.core.monadErrorInstance[Any, Throwable]

      test("do nothing on success") {
        for {
          ref <- Ref.make(1)
          _ <- (ZIO.succeed(3): Task[Int]).onSqlException(ref.update(_ + 1)).either
          a <- ref.get
        } yield {
          assertTrue(a == 1)
        }
      } ::
      test("perform its effect on SQLException") {
        val e = new SQLException("", SQLSTATE_FOO.value)
        for {
          ref <- Ref.make(1)
          result <- (ZIO.fail(e): Task[Int]).onSqlException(ref.update(_ + 1)).either
          a <- ref.get
        } yield {
          assertTrue(result == Left(e)) &&
          assertTrue(a == 2)
        }
      } ::
      test("ignore its effect on non-SQLException") {
        val e = new IllegalArgumentException
        for {
          ref <- Ref.make(1)
          result <- (ZIO.fail(e): Task[Int]).onSqlException(ref.update(_ + 1)).either
          a <- ref.get
        } yield {
          assertTrue(result == Left(e)) &&
          assertTrue(a == 1)
        }
      } :: Nil
    }),
  )

}
