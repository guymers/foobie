// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.hi

import cats.Alternative
import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.monad.*
import doobie.FRS
import doobie.enumerated.FetchDirection
import doobie.enumerated.Holdability
import doobie.free.resultset.ResultSetIO
import doobie.util.Read
import doobie.util.Write
import doobie.util.invariant.*
import doobie.util.stream.repeatEvalChunks
import fs2.Stream

import java.sql.ResultSetMetaData
import java.sql.SQLWarning
import scala.collection.Factory

/**
 * Module of high-level constructors for `ResultSetIO` actions.
 * @group Modules
 */
object resultset {

  /**
   * Non-strict unit for capturing effects.
   * @group Constructors (Lifting)
   */
  def delay[A](a: => A): ResultSetIO[A] =
    FRS.delay(a)

  /** @group Cursor Control */
  def absolute(row: Int): ResultSetIO[Boolean] =
    FRS.absolute(row)

  /** @group Cursor Control */
  val afterLast: ResultSetIO[Unit] =
    FRS.afterLast

  /** @group Cursor Control */
  val beforeFirst: ResultSetIO[Unit] =
    FRS.beforeFirst

  /** @group Updating */
  val cancelRowUpdates: ResultSetIO[Unit] =
    FRS.cancelRowUpdates

  /** @group Warnings */
  val clearWarnings: ResultSetIO[Unit] =
    FRS.clearWarnings

  /** @group Updating */
  val deleteRow: ResultSetIO[Unit] =
    FRS.deleteRow

  /** @group Cursor Control */
  val first: ResultSetIO[Boolean] =
    FRS.first

  /**
   * Read a value of type `A`.
   * @group Results
   */
  def get[A](implicit A: Read[A]): ResultSetIO[A] =
    A.get

  /**
   * Consumes the remainder of the resultset, reading each row as a value of
   * type `A` and accumulating them in a standard library collection via
   * `CanBuildFrom`.
   * @group Results
   */
  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures", "org.wartremover.warts.While"))
  def build[F[_], A](implicit F: Factory[A, F[A]], A: Read[A]): ResultSetIO[F[A]] =
    FRS.raw { rs =>
      val b = F.newBuilder
      while (rs.next) {
        val _ = b += A.unsafeGet(rs, 1)
      }
      b.result()
    }

  /**
   * Consumes the remainder of the resultset, reading each row as a value of
   * type `(A, B)` and accumulating them in a standard library collection via
   * `CanBuildFrom`.
   * @group Results
   */
  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures", "org.wartremover.warts.While"))
  def buildPair[F[_, _], A, B](implicit F: Factory[(A, B), F[A, B]], A: Read[(A, B)]): ResultSetIO[F[A, B]] =
    FRS.raw { rs =>
      val b = F.newBuilder
      while (rs.next) {
        val _ = b += A.unsafeGet(rs, 1)
      }
      b.result()
    }

  /**
   * Consumes the remainder of the resultset, reading each row as a value of
   * type `A`, mapping to `B`, and accumulating them in a standard library
   * collection via `CanBuildFrom`. This unusual constructor is a workaround for
   * the CanBuildFrom not having a sensible contravariant functor instance.
   * @group Results
   */
  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures", "org.wartremover.warts.While"))
  def buildMap[F[_], A, B](f: A => B)(implicit F: Factory[B, F[B]], A: Read[A]): ResultSetIO[F[B]] =
    FRS.raw { rs =>
      val b = F.newBuilder
      while (rs.next) {
        val _ = b += f(A.unsafeGet(rs, 1))
      }
      b.result()
    }

  /**
   * Consumes the remainder of the resultset, reading each row as a value of
   * type `A` and accumulating them in a `Vector`.
   * @group Results
   */
  def vector[A: Read]: ResultSetIO[Vector[A]] =
    build[Vector, A]

  /**
   * Consumes the remainder of the resultset, reading each row as a value of
   * type `A` and accumulating them in a `List`.
   * @group Results
   */
  def list[A: Read]: ResultSetIO[List[A]] =
    build[List, A]

  /**
   * Like `getNext` but loops until the end of the resultset, gathering results
   * in a `MonadPlus`.
   * @group Results
   */
  def accumulate[G[_]: Alternative, A: Read]: ResultSetIO[G[A]] =
    get[A].whileM(next)

  /**
   * Updates a value of type `A`.
   * @group Updating
   */
  def update[A](a: A)(implicit A: Write[A]): ResultSetIO[Unit] =
    A.update(a)

  /**
   * Similar to `next >> get` but lifted into `Option`; returns `None` when no
   * more rows are available.
   * @group Results
   */
  def getNext[A: Read]: ResultSetIO[Option[A]] =
    next >>= {
      case true => get[A].map(Some(_))
      case false => Monad[ResultSetIO].pure(None)
    }

  /**
   * Similar to `getNext` but reads `chunkSize` rows at a time (the final chunk
   * in a resultset may be smaller). A non-positive `chunkSize` yields an empty
   * `Seq` and consumes no rows. This method delegates to `getNextChunkV` and
   * widens to `Seq` for easier interoperability with streaming libraries that
   * like `Seq` better.
   * @group Results
   */
  def getNextChunk[A: Read](chunkSize: Int): ResultSetIO[Seq[A]] =
    getNextChunkV[A](chunkSize).widen[Seq[A]]

  /**
   * Similar to `getNext` but reads `chunkSize` rows at a time (the final chunk
   * in a resultset may be smaller). A non-positive `chunkSize` yields an empty
   * `Vector` and consumes no rows.
   * @group Results
   */
  @SuppressWarnings(Array(
    "org.wartremover.warts.MutableDataStructures",
    "org.wartremover.warts.Var",
    "org.wartremover.warts.While",
  ))
  def getNextChunkV[A](chunkSize: Int)(implicit A: Read[A]): ResultSetIO[Vector[A]] =
    FRS.raw { rs =>
      var n = chunkSize
      val b = Vector.newBuilder[A]
      while (n > 0 && rs.next) {
        val _ = b += A.unsafeGet(rs, 1)
        n -= 1
      }
      b.result()
    }

  /**
   * Equivalent to `getNext`, but verifies that there is exactly one row
   * remaining.
   * @throws doobie.util.invariant.UnexpectedCursorPosition
   *   if there is not exactly one row remaining
   * @group Results
   */
  def getUnique[A: Read]: ResultSetIO[A] =
    (getNext[A], next).tupled.flatMap {
      case (Some(a), false) => FRS.delay(a)
      case (Some(_), true) => FRS.raiseError(UnexpectedContinuation())
      case (None, _) => FRS.raiseError(UnexpectedEnd())
    }

  /**
   * Equivalent to `getNext`, but verifies that there is at most one row
   * remaining.
   * @throws doobie.util.invariant.UnexpectedContinuation
   *   if there is more than one row remaining
   * @group Results
   */
  def getOption[A: Read]: ResultSetIO[Option[A]] =
    (getNext[A], next).tupled.flatMap {
      case (a @ Some(_), false) => FRS.delay(a)
      case (Some(_), true) => FRS.raiseError(UnexpectedContinuation())
      case (None, _) => FRS.delay(None)
    }

  /**
   * Consumes the remainder of the resultset, but verifies that there is at
   * least one row remaining.
   * @throws doobie.util.invariant.UnexpectedEnd
   *   if there is not at least one row remaining
   * @group Results
   */
  def nel[A: Read]: ResultSetIO[NonEmptyList[A]] =
    (getNext[A], list).tupled.flatMap {
      case (Some(a), as) => FRS.delay(NonEmptyList(a, as))
      case (None, _) => FRS.raiseError(UnexpectedEnd())
    }

  /**
   * Stream that reads from the `ResultSet` and returns a stream of `A`s. This
   * is the preferred mechanism for dealing with query results.
   * @group Results
   */
  def stream[A: Read](chunkSize: Int): Stream[ResultSetIO, A] =
    repeatEvalChunks(getNextChunk[A](chunkSize))

  /** @group Properties */
  val getFetchDirection: ResultSetIO[FetchDirection] =
    FRS.getFetchDirection.flatMap(FetchDirection.fromIntF[ResultSetIO])

  /** @group Properties */
  val getFetchSize: ResultSetIO[Int] =
    FRS.getFetchSize

  /** @group Properties */
  val getHoldability: ResultSetIO[Holdability] =
    FRS.getHoldability.flatMap(Holdability.fromIntF[ResultSetIO])

  /** @group Properties */
  val getMetaData: ResultSetIO[ResultSetMetaData] =
    FRS.getMetaData

  /** @group Cursor Control */
  val getRow: ResultSetIO[Int] =
    FRS.getRow

  /** @group Warnings */
  val getWarnings: ResultSetIO[Option[SQLWarning]] =
    FRS.getWarnings.map(Option(_))

  /** @group Updating */
  val insertRow: ResultSetIO[Unit] =
    FRS.insertRow

  /** @group Cursor Control */
  val isAfterLast: ResultSetIO[Boolean] =
    FRS.isAfterLast

  /** @group Cursor Control */
  val isBeforeFirst: ResultSetIO[Boolean] =
    FRS.isBeforeFirst

  /** @group Cursor Control */
  val isFirst: ResultSetIO[Boolean] =
    FRS.isFirst

  /** @group Cursor Control */
  val isLast: ResultSetIO[Boolean] =
    FRS.isLast

  /** @group Cursor Control */
  val last: ResultSetIO[Boolean] =
    FRS.last

  /** @group Cursor Control */
  val moveToCurrentRow: ResultSetIO[Unit] =
    FRS.moveToCurrentRow

  /** @group Cursor Control */
  val moveToInsertRow: ResultSetIO[Unit] =
    FRS.moveToInsertRow

  /** @group Cursor Control */
  val next: ResultSetIO[Boolean] =
    FRS.next

  /** @group Cursor Control */
  val previous: ResultSetIO[Boolean] =
    FRS.previous

  /** @group Cursor Control */
  val refreshRow: ResultSetIO[Unit] =
    FRS.refreshRow

  /** @group Cursor Control */
  def relative(n: Int): ResultSetIO[Boolean] =
    FRS.relative(n)

  /** @group Cursor Control */
  val rowDeleted: ResultSetIO[Boolean] =
    FRS.rowDeleted

  /** @group Cursor Control */
  val rowInserted: ResultSetIO[Boolean] =
    FRS.rowInserted

  /** @group Cursor Control */
  val rowUpdated: ResultSetIO[Boolean] =
    FRS.rowUpdated

  /** @group Properties */
  def setFetchDirection(fd: FetchDirection): ResultSetIO[Unit] =
    FRS.setFetchDirection(fd.toInt)

  /** @group Properties */
  def setFetchSize(n: Int): ResultSetIO[Unit] =
    FRS.setFetchSize(n)

}
