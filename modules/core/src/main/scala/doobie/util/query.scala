// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.Alternative
import cats.Contravariant
import cats.Functor
import cats.arrow.Profunctor
import cats.data.NonEmptyList
import cats.syntax.all.*
import doobie.HC
import doobie.HPS
import doobie.HRS
import doobie.free.connection.ConnectionIO
import doobie.free.preparedstatement.PreparedStatementIO
import doobie.free.resultset.ResultSetIO
import doobie.util.analysis.Analysis
import doobie.util.fragment.Fragment
import doobie.util.pos.Pos
import fs2.Stream

import scala.collection.Factory

/** Module defining queries parameterized by input and output types. */
object query {

  val DefaultChunkSize = 512

  /**
   * A query parameterized by some input type `A` yielding values of type `B`.
   * We define here the core operations that are needed. Additional operations
   * are provided on [[Query0]] which is the residual query after applying an
   * `A`. This is the type constructed by the `sql` interpolator.
   */
  trait Query[A, B] { outer =>

    protected implicit val write: Write[A]
    protected implicit val read: Read[B]

    /**
     * The SQL string.
     * @group Diagnostics
     */
    def sql: String

    /**
     * An optional [[Pos]] indicating the source location where this [[Query]]
     * was constructed. This is used only for diagnostic purposes.
     * @group Diagnostics
     */
    def pos: Option[Pos]

    /** Convert this Query to a [[Fragment]]. */
    def toFragment(a: A): Fragment =
      write.toFragment(a, sql)

    /**
     * Program to construct an analysis of this query's SQL statement and
     * asserted parameter and column types.
     * @group Diagnostics
     */
    def analysis: ConnectionIO[Analysis] =
      HC.prepareQueryAnalysis[A, B](sql)

    /**
     * Program to construct an analysis of this query's SQL statement and result
     * set column types.
     * @group Diagnostics
     */
    def outputAnalysis: ConnectionIO[Analysis] =
      HC.prepareQueryAnalysis0[B](sql)

    /**
     * Program to construct an inspection of the query. Given arguments `a`,
     * calls `f` with the SQL representation of the query and a statement with
     * all arguments set. Returns the result of the `ConnectionIO` program
     * constructed.
     *
     * @group Diagnostics
     */
    def inspect[R](a: A)(f: (String, PreparedStatementIO[Unit]) => ConnectionIO[R]): ConnectionIO[R] =
      f(sql, HPS.set(a))

    /**
     * Apply the argument `a` to construct a `Stream` with the given chunking
     * factor, with effect type [[ConnectionIO]] yielding elements of type `B`.
     * @group Results
     */
    def streamWithChunkSize(a: A, chunkSize: Int): Stream[ConnectionIO, B] =
      HC.stream[B](sql, HPS.set(a), chunkSize)

    /**
     * Apply the argument `a` to construct a `Stream` with `DefaultChunkSize`,
     * with effect type [[ConnectionIO]] yielding elements of type `B`.
     * @group Results
     */
    def stream(a: A): Stream[ConnectionIO, B] =
      streamWithChunkSize(a, DefaultChunkSize)

    /**
     * Apply the argument `a` to construct a program in [[ConnectionIO]]
     * yielding an `F[B]`. This is the fastest way to accumulate a collection.
     * @group Results
     */
    def to[F[_]](a: A)(implicit f: Factory[B, F[B]]): ConnectionIO[F[B]] =
      withPrepareStatement(a, HRS.build[F, B])

    /**
     * Apply the argument `a` to construct a program in [[ConnectionIO]]
     * yielding an `Map[(K, V)]` accumulated via the provided `CanBuildFrom`.
     * This is the fastest way to accumulate a collection. this function can
     * call only when B is (K, V).
     * @group Results
     */
    def toMap[K, V](a: A)(implicit ev: B =:= (K, V), f: Factory[(K, V), Map[K, V]]): ConnectionIO[Map[K, V]] =
      withPrepareStatement(a, HRS.buildPair[Map, K, V](f, read.map(ev)))

    /**
     * Apply the argument `a` to construct a program in [[ConnectionIO]]
     * yielding an `F[B]` accumulated via `MonadPlus` append. This method is
     * more general but less efficient than `to`.
     * @group Results
     */
    def accumulate[F[_]: Alternative](a: A): ConnectionIO[F[B]] =
      withPrepareStatement(a, HRS.accumulate[F, B])

    /**
     * Apply the argument `a` to construct a program in [[ConnectionIO]]
     * yielding a unique `B` and raising an exception if the resultset does not
     * have exactly one row. See also `option`.
     * @group Results
     */
    def unique(a: A): ConnectionIO[B] =
      withPrepareStatement(a, HRS.getUnique[B])

    /**
     * Apply the argument `a` to construct a program in [[ConnectionIO]]
     * yielding an optional `B` and raising an exception if the resultset has
     * more than one row. See also `unique`.
     * @group Results
     */
    def option(a: A): ConnectionIO[Option[B]] =
      withPrepareStatement(a, HRS.getOption[B])

    /**
     * Apply the argument `a` to construct a program in [[ConnectionIO]]
     * yielding an `NonEmptyList[B]` and raising an exception if the resultset
     * does not have at least one row. See also `unique`.
     * @group Results
     */
    def nel(a: A): ConnectionIO[NonEmptyList[B]] =
      withPrepareStatement(a, HRS.nel[B])

    private def withPrepareStatement[T](a: A, k: ResultSetIO[T]): ConnectionIO[T] =
      HC.prepareStatement(sql)(HPS.set(a) *> HPS.executeQuery(k))

    /** @group Transformations */
    def map[C](f: B => C): Query[A, C] =
      new Query[A, C] {
        val write = outer.write
        val read = outer.read.map(f)
        def sql = outer.sql
        def pos = outer.pos
      }

    /** @group Transformations */
    def contramap[C](f: C => A): Query[C, B] =
      new Query[C, B] {
        val write = outer.write.contramap(f)
        val read = outer.read
        def sql = outer.sql
        def pos = outer.pos
      }

    /**
     * Apply an argument, yielding a residual [[Query0]].
     * @group Transformations
     */
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def toQuery0(a: A): Query0[B] =
      new Query0[B] {
        def sql = outer.sql
        def pos = outer.pos
        def toFragment = outer.toFragment(a)
        def analysis = outer.analysis
        def outputAnalysis = outer.outputAnalysis
        def streamWithChunkSize(n: Int) = outer.streamWithChunkSize(a, n)
        def accumulate[F[_]: Alternative] = outer.accumulate[F](a)
        def to[F[_]](implicit f: Factory[B, F[B]]) = outer.to[F](a)
        def toMap[K, V](implicit ev: B =:= (K, V), f: Factory[(K, V), Map[K, V]]) = outer.toMap(a)
        def unique = outer.unique(a)
        def option = outer.option(a)
        def nel = outer.nel(a)
        def map[C](f: B => C): Query0[C] = outer.map(f).toQuery0(a)
        def inspect[R](f: (String, PreparedStatementIO[Unit]) => ConnectionIO[R]) = outer.inspect(a)(f)
      }

  }

  object Query {

    /**
     * Construct a `Query` with the given SQL string, an optional `Pos` for
     * diagnostic purposes, and type arguments for writable input and readable
     * output types. Note that the most common way to construct a `Query` is via
     * the `sql` interpolator.
     * @group Constructors
     */
    @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
    def apply[A, B](sql0: String, pos0: Option[Pos] = None)(implicit
      A: Write[A],
      B: Read[B],
    ): Query[A, B] = new Query[A, B] {
      val write = A
      val read = B
      val sql = sql0
      val pos = pos0
    }

    /** @group Typeclass Instances */
    implicit val queryProfunctor: Profunctor[Query] = new Profunctor[Query] {
      override def dimap[A, B, C, D](fab: Query[A, B])(f: C => A)(g: B => D) = fab.contramap(f).map(g)
    }

    /** @group Typeclass Instances */
    implicit def queryCovariant[A]: Functor[Query[A, *]] = new Functor[Query[A, *]] {
      override def map[B, C](fa: Query[A, B])(f: B => C) = fa.map(f)
    }

    /** @group Typeclass Instances */
    implicit def queryContravariant[B]: Contravariant[Query[*, B]] = new Contravariant[Query[*, B]] {
      override def contramap[A, C](fa: Query[A, B])(f: C => A) = fa.contramap(f)
    }

  }

  /**
   * An abstract query closed over its input arguments and yielding values of
   * type `B`, without a specified disposition. Methods provided on [[Query0]]
   * allow the query to be interpreted as a stream or program in `CollectionIO`.
   */
  trait Query0[B] { outer =>

    /**
     * The SQL string.
     * @group Diagnostics
     */
    def sql: String

    /**
     * An optional `Pos` indicating the source location where this `Query` was
     * constructed. This is used only for diagnostic purposes.
     * @group Diagnostics
     */
    def pos: Option[Pos]

    /**
     * Program to construct an analysis of this query's SQL statement and
     * asserted parameter and column types.
     * @group Diagnostics
     */
    def analysis: ConnectionIO[Analysis]

    /** Convert this Query0 to a `Fragment`. */
    def toFragment: Fragment

    /**
     * Program to construct an inspection of the query. Calls `f` with the SQL
     * representation of the query and a statement with all statement arguments
     * set. Returns the result of the `ConnectionIO` program constructed.
     *
     * @group Diagnostics
     */
    def inspect[R](f: (String, PreparedStatementIO[Unit]) => ConnectionIO[R]): ConnectionIO[R]

    /**
     * Program to construct an analysis of this query's SQL statement and result
     * set column types.
     * @group Diagnostics
     */
    def outputAnalysis: ConnectionIO[Analysis]

    /**
     * `Stream` with default chunk factor, with effect type [[ConnectionIO]]
     * yielding elements of type `B`.
     * @group Results
     */
    def stream: Stream[ConnectionIO, B] =
      streamWithChunkSize(DefaultChunkSize)

    /**
     * `Stream` with given chunk factor, with effect type [[ConnectionIO]]
     * yielding elements of type `B`.
     * @group Results
     */
    def streamWithChunkSize(n: Int): Stream[ConnectionIO, B]

    /**
     * Program in [[ConnectionIO]] yielding an `F[B]` accumulated via the
     * provided `CanBuildFrom`. This is the fastest way to accumulate a
     * collection.
     * @group Results
     */
    def to[F[_]](implicit f: Factory[B, F[B]]): ConnectionIO[F[B]]

    /**
     * Apply the argument `a` to construct a program in [[ConnectionIO]]
     * yielding an `Map[(K, V)]` accumulated via the provided `CanBuildFrom`.
     * This is the fastest way to accumulate a collection. this function can
     * call only when B is (K, V).
     * @group Results
     */
    def toMap[K, V](implicit ev: B =:= (K, V), f: Factory[(K, V), Map[K, V]]): ConnectionIO[Map[K, V]]

    /**
     * Program in [[ConnectionIO]] yielding an `F[B]` accumulated via
     * `MonadPlus` append. This method is more general but less efficient than
     * `to`.
     * @group Results
     */
    def accumulate[F[_]: Alternative]: ConnectionIO[F[B]]

    /**
     * Program in [[ConnectionIO]] yielding a unique `B` and raising an
     * exception if the resultset does not have exactly one row. See also
     * `option`.
     * @group Results
     */
    def unique: ConnectionIO[B]

    /**
     * Program in [[ConnectionIO]] yielding an optional `B` and raising an
     * exception if the resultset has more than one row. See also `unique`.
     * @group Results
     */
    def option: ConnectionIO[Option[B]]

    /**
     * Program in [[ConnectionIO]] yielding a `NonEmptyList[B]` and raising an
     * exception if the resultset does not have at least one row. See also
     * `unique`.
     * @group Results
     */
    def nel: ConnectionIO[NonEmptyList[B]]

    /** @group Transformations */
    def map[C](f: B => C): Query0[C]

  }

  object Query0 {

    /**
     * Construct a `Query` with the given SQL string, an optional `Pos` for
     * diagnostic purposes, with no parameters. Note that the most common way to
     * construct a `Query` is via the `sql`interpolator.
     * @group Constructors
     */
    @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
    def apply[A: Read](sql: String, pos: Option[Pos] = None): Query0[A] =
      Query[Unit, A](sql, pos).toQuery0(())

    /** @group Typeclass Instances */
    implicit val queryFunctor: Functor[Query0] = new Functor[Query0] {
      override def map[A, B](fa: Query0[A])(f: A => B) = fa.map(f)
    }

  }

}
