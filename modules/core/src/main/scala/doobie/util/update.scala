// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.Contravariant
import cats.Foldable
import doobie.free.connection.ConnectionIO
import doobie.util.analysis.Analysis
import doobie.util.fragment.Fragment
import doobie.util.pos.Pos

import scala.collection.Factory

/** Module defining updates parameterized by input type. */
object update {

  trait UpdateManyReturningGeneratedKeysPartiallyApplied[A, K] {
    def apply[F[_]](as: F[A])(implicit F: Foldable[F], K: Read[K], B: Factory[K, F[K]]): ConnectionIO[F[K]]
  }

  /**
   * An update parameterized by some input type `A`. This is the type
   * constructed by the `sql` interpolator.
   */
  trait Update[A] { u =>

    // Contravariant coyoneda trick for A
    protected implicit val write: Write[A]

    /**
     * The SQL string.
     * @group Diagnostics
     */
    val sql: String

    /**
     * An optional [[Pos]] indicating the source location where this [[Update]]
     * was constructed. This is used only for diagnostic purposes.
     * @group Diagnostics
     */
    val pos: Option[Pos]

    /** Convert this Update to a `Fragment`. */
    def toFragment(a: A): Fragment =
      write.toFragment(a, sql)

    /**
     * Program to construct an analysis of this query's SQL statement and
     * asserted parameter types.
     * @group Diagnostics
     */
    def analysis: ConnectionIO[Analysis] =
      ConnectionIO.prepareUpdateAnalysis[A](sql)

    /**
     * Program to construct an analysis of this query's SQL statement and result
     * set column types.
     * @group Diagnostics
     */
    def outputAnalysis: ConnectionIO[Analysis] =
      ConnectionIO.prepareUpdateAnalysis0(sql)

    /**
     * Construct a program to execute the update and yield a count of affected
     * rows, given the writable argument `a`.
     * @group Execution
     */
    def run(a: A): ConnectionIO[Int] =
      ConnectionIO.update.run(sql, a)

    /**
     * Program to execute a batch update and yield a count of affected rows.
     * Note that failed updates are not reported (see
     * https://github.com/tpolecat/doobie/issues/706). This API is likely to
     * change.
     * @group Execution
     */
    def updateMany[F[_]: Foldable](fa: F[A]): ConnectionIO[Int] =
      ConnectionIO.update.many(sql, fa)

    /**
     * Perform a batch update as with [[updateMany]] yielding generated keys of
     * readable type `K`, identified by the specified columns. Note that not all
     * drivers support generated keys, and some support only a single key
     * column.
     * @group Execution
     */
    def updateManyReturningGeneratedKeys[K](columns: String*): UpdateManyReturningGeneratedKeysPartiallyApplied[A, K] =
      new UpdateManyReturningGeneratedKeysPartiallyApplied[A, K] {
        override def apply[F[_]](fa: F[A])(implicit F: Foldable[F], K: Read[K], B: Factory[K, F[K]]) =
          ConnectionIO.update.manyReturningGeneratedKeys(sql, fa, columns.toList)
      }

    /**
     * Construct a program that performs the update, yielding a single set of
     * generated keys of readable type `K`, identified by the specified columns,
     * given a writable argument `a`. Note that not all drivers support
     * generated keys, and some support only a single key column.
     * @group Execution
     */
    def withUniqueGeneratedKeys[K: Read](columns: String*)(a: A): ConnectionIO[K] =
      ConnectionIO.update.generatedKeysUnique(sql, a, columns.toList)

    /**
     * Update is a contravariant functor.
     * @group Transformations
     */
    def contramap[C](f: C => A): Update[C] = new Update[C] {
      val write = u.write.contramap(f)
      val sql = u.sql
      val pos = u.pos
    }

    /**
     * Apply an argument, yielding a residual [[Update0]].
     * @group Transformations
     */
    def toUpdate0(a: A): Update0 = new Update0 {
      val sql = u.sql
      val pos = u.pos
      def toFragment: Fragment = u.toFragment(a)
      def analysis = u.analysis
      def outputAnalysis = u.outputAnalysis
      def run = u.run(a)
      def withUniqueGeneratedKeys[K: Read](columns: String*) = u.withUniqueGeneratedKeys(columns*)(a)
    }

  }

  object Update {

    /**
     * Construct an `Update` for some writable parameter type `A` with the given
     * SQL string, and optionally a `Pos` for diagnostics. The normal mechanism
     * for construction is the `sql/fr/fr0` interpolators.
     * @group Constructors
     */
    @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
    def apply[A](sql0: String, pos0: Option[Pos] = None)(implicit W: Write[A]): Update[A] = new Update[A] {
      val write = W
      val sql = sql0
      val pos = pos0
    }

    /**
     * Update is a contravariant functor.
     * @group Typeclass Instances
     */
    implicit val updateContravariant: Contravariant[Update] = new Contravariant[Update] {
      override def contramap[A, B](fa: Update[A])(f: B => A) = fa.contramap(f)
    }

  }

  trait Update0 {

    /**
     * The SQL string.
     * @group Diagnostics
     */
    val sql: String

    /**
     * An optional [[Pos]] indicating the source location where this [[Query]]
     * was constructed. This is used only for diagnostic purposes.
     * @group Diagnostics
     */
    val pos: Option[Pos]

    /** Convert this Update0 to a `Fragment`. */
    def toFragment: Fragment

    /**
     * Program to construct an analysis of this query's SQL statement and
     * asserted parameter types.
     * @group Diagnostics
     */
    def analysis: ConnectionIO[Analysis]

    /**
     * Program to construct an analysis of this query's SQL statement and result
     * set column types.
     * @group Diagnostics
     */
    def outputAnalysis: ConnectionIO[Analysis]

    /**
     * Program to execute the update and yield a count of affected rows.
     * @group Execution
     */
    def run: ConnectionIO[Int]

    /**
     * Construct a program that performs the update, yielding a single set of
     * generated keys of readable type `K`, identified by the specified columns.
     * Note that not all drivers support generated keys, and some support only a
     * single key column.
     * @group Execution
     */
    def withUniqueGeneratedKeys[K: Read](columns: String*): ConnectionIO[K]

  }

  object Update0 {

    /**
     * Construct an `Update0` with the given SQL string, and optionally a `Pos`
     * for diagnostics. The normal mechanism for construction is the
     * `sql/fr/fr0` interpolators.
     * @group Constructors
     */
    def apply(sql0: String, pos0: Option[Pos]): Update0 =
      Update[Unit](sql0, pos0).toUpdate0(())

  }

}
