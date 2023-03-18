// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.free

import cats.Monoid
import cats.effect.kernel.CancelScope
import cats.effect.kernel.Poll
import cats.effect.kernel.Sync
import cats.free.Free as FF // alias because some algebras have an op called Free
import cats.~>
import doobie.WeakAsync

import java.sql.Connection
import java.sql.Driver
import java.sql.DriverPropertyInfo
import java.util.Properties
import java.util.logging.Logger
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

object driver { module =>

  // Algebra of operations for Driver. Each accepts a visitor as an alternative to pattern-matching.
  sealed trait DriverOp[A] {
    def visit[F[_]](v: DriverOp.Visitor[F]): F[A]
  }

  // Free monad over DriverOp.
  type DriverIO[A] = FF[DriverOp, A]

  // Module of instances and constructors of DriverOp.
  @SuppressWarnings(Array("org.wartremover.warts.ArrayEquals"))
  object DriverOp {

    // Given a Driver we can embed a DriverIO program in any algebra that understands embedding.
    implicit val DriverOpEmbeddable: Embeddable[DriverOp, Driver] =
      new Embeddable[DriverOp, Driver] {
        def embed[A](j: Driver, fa: FF[DriverOp, A]) = Embedded.Driver(j, fa)
      }

    // Interface for a natural transformation DriverOp ~> F encoded via the visitor pattern.
    // This approach is much more efficient than pattern-matching for large algebras.
    trait Visitor[F[_]] extends (DriverOp ~> F) {
      final def apply[A](fa: DriverOp[A]): F[A] = fa.visit(this)

      // Common
      def raw[A](f: Driver => A): F[A]
      def embed[A](e: Embedded[A]): F[A]
      def raiseError[A](e: Throwable): F[A]
      def handleErrorWith[A](fa: DriverIO[A])(f: Throwable => DriverIO[A]): F[A]
      def monotonic: F[FiniteDuration]
      def realTime: F[FiniteDuration]
      def delay[A](thunk: => A): F[A]
      def suspend[A](hint: Sync.Type)(thunk: => A): F[A]
      def forceR[A, B](fa: DriverIO[A])(fb: DriverIO[B]): F[B]
      def uncancelable[A](body: Poll[DriverIO] => DriverIO[A]): F[A]
      def poll[A](poll: Any, fa: DriverIO[A]): F[A]
      def canceled: F[Unit]
      def onCancel[A](fa: DriverIO[A], fin: DriverIO[Unit]): F[A]
      def fromFuture[A](fut: DriverIO[Future[A]]): F[A]

      // Driver
      def acceptsURL(a: String): F[Boolean]
      def connect(a: String, b: Properties): F[Connection]
      def getMajorVersion: F[Int]
      def getMinorVersion: F[Int]
      def getParentLogger: F[Logger]
      def getPropertyInfo(a: String, b: Properties): F[Array[DriverPropertyInfo]]
      def jdbcCompliant: F[Boolean]

    }

    // Common operations for all algebras.
    final case class Raw[A](f: Driver => A) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.raw(f)
    }
    final case class Embed[A](e: Embedded[A]) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.embed(e)
    }
    final case class RaiseError[A](e: Throwable) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.raiseError(e)
    }
    final case class HandleErrorWith[A](fa: DriverIO[A], f: Throwable => DriverIO[A]) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.handleErrorWith(fa)(f)
    }
    case object Monotonic extends DriverOp[FiniteDuration] {
      def visit[F[_]](v: Visitor[F]) = v.monotonic
    }
    case object Realtime extends DriverOp[FiniteDuration] {
      def visit[F[_]](v: Visitor[F]) = v.realTime
    }
    final case class Suspend[A](hint: Sync.Type, thunk: () => A) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.suspend(hint)(thunk())
    }
    final case class ForceR[A, B](fa: DriverIO[A], fb: DriverIO[B]) extends DriverOp[B] {
      def visit[F[_]](v: Visitor[F]) = v.forceR(fa)(fb)
    }
    final case class Uncancelable[A](body: Poll[DriverIO] => DriverIO[A]) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.uncancelable(body)
    }
    final case class Poll1[A](poll: Any, fa: DriverIO[A]) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.poll(poll, fa)
    }
    case object Canceled extends DriverOp[Unit] {
      def visit[F[_]](v: Visitor[F]) = v.canceled
    }
    final case class OnCancel[A](fa: DriverIO[A], fin: DriverIO[Unit]) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.onCancel(fa, fin)
    }
    final case class FromFuture[A](fut: DriverIO[Future[A]]) extends DriverOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.fromFuture(fut)
    }

    // Driver-specific operations.
    final case class AcceptsURL(a: String) extends DriverOp[Boolean] {
      def visit[F[_]](v: Visitor[F]) = v.acceptsURL(a)
    }
    final case class Connect(a: String, b: Properties) extends DriverOp[Connection] {
      def visit[F[_]](v: Visitor[F]) = v.connect(a, b)
    }
    case object GetMajorVersion extends DriverOp[Int] {
      def visit[F[_]](v: Visitor[F]) = v.getMajorVersion
    }
    case object GetMinorVersion extends DriverOp[Int] {
      def visit[F[_]](v: Visitor[F]) = v.getMinorVersion
    }
    case object GetParentLogger extends DriverOp[Logger] {
      def visit[F[_]](v: Visitor[F]) = v.getParentLogger
    }
    final case class GetPropertyInfo(a: String, b: Properties) extends DriverOp[Array[DriverPropertyInfo]] {
      def visit[F[_]](v: Visitor[F]) = v.getPropertyInfo(a, b)
    }
    case object JdbcCompliant extends DriverOp[Boolean] {
      def visit[F[_]](v: Visitor[F]) = v.jdbcCompliant
    }

  }
  import DriverOp.*

  // Smart constructors for operations common to all algebras.
  val unit: DriverIO[Unit] = FF.pure[DriverOp, Unit](())
  def pure[A](a: A): DriverIO[A] = FF.pure[DriverOp, A](a)
  def raw[A](f: Driver => A): DriverIO[A] = FF.liftF(Raw(f))
  def embed[F[_], J, A](j: J, fa: FF[F, A])(implicit ev: Embeddable[F, J]): FF[DriverOp, A] =
    FF.liftF(Embed(ev.embed(j, fa)))
  def raiseError[A](err: Throwable): DriverIO[A] = FF.liftF[DriverOp, A](RaiseError(err))
  def handleErrorWith[A](fa: DriverIO[A])(f: Throwable => DriverIO[A]): DriverIO[A] =
    FF.liftF[DriverOp, A](HandleErrorWith(fa, f))
  val monotonic = FF.liftF[DriverOp, FiniteDuration](Monotonic)
  val realtime = FF.liftF[DriverOp, FiniteDuration](Realtime)
  def delay[A](thunk: => A) = FF.liftF[DriverOp, A](Suspend(Sync.Type.Delay, () => thunk))
  def suspend[A](hint: Sync.Type)(thunk: => A) = FF.liftF[DriverOp, A](Suspend(hint, () => thunk))
  def forceR[A, B](fa: DriverIO[A])(fb: DriverIO[B]) = FF.liftF[DriverOp, B](ForceR(fa, fb))
  def uncancelable[A](body: Poll[DriverIO] => DriverIO[A]) = FF.liftF[DriverOp, A](Uncancelable(body))
  def capturePoll[M[_]](mpoll: Poll[M]) = new Poll[DriverIO] {
    def apply[A](fa: DriverIO[A]) = FF.liftF[DriverOp, A](Poll1(mpoll, fa))
  }
  val canceled = FF.liftF[DriverOp, Unit](Canceled)
  def onCancel[A](fa: DriverIO[A], fin: DriverIO[Unit]) = FF.liftF[DriverOp, A](OnCancel(fa, fin))
  def fromFuture[A](fut: DriverIO[Future[A]]) = FF.liftF[DriverOp, A](FromFuture(fut))

  // Smart constructors for Driver-specific operations.
  def acceptsURL(a: String): DriverIO[Boolean] = FF.liftF(AcceptsURL(a))
  def connect(a: String, b: Properties): DriverIO[Connection] = FF.liftF(Connect(a, b))
  val getMajorVersion: DriverIO[Int] = FF.liftF(GetMajorVersion)
  val getMinorVersion: DriverIO[Int] = FF.liftF(GetMinorVersion)
  val getParentLogger: DriverIO[Logger] = FF.liftF(GetParentLogger)
  def getPropertyInfo(a: String, b: Properties): DriverIO[Array[DriverPropertyInfo]] = FF.liftF(GetPropertyInfo(a, b))
  val jdbcCompliant: DriverIO[Boolean] = FF.liftF(JdbcCompliant)

  private val monad = FF.catsFreeMonadForFree[DriverOp]

  // Typeclass instances for DriverIO
  implicit val WeakAsyncDriverIO: WeakAsync[DriverIO] =
    new WeakAsync[DriverIO] {
      override val applicative = monad
      override val rootCancelScope = CancelScope.Cancelable
      override def pure[A](x: A): DriverIO[A] = monad.pure(x)
      override def map[A, B](fa: DriverIO[A])(f: A => B) = monad.map(fa)(f)
      override def flatMap[A, B](fa: DriverIO[A])(f: A => DriverIO[B]): DriverIO[B] = monad.flatMap(fa)(f)
      override def tailRecM[A, B](a: A)(f: A => DriverIO[Either[A, B]]): DriverIO[B] = monad.tailRecM(a)(f)
      override def raiseError[A](e: Throwable): DriverIO[A] = module.raiseError(e)
      override def handleErrorWith[A](fa: DriverIO[A])(f: Throwable => DriverIO[A]): DriverIO[A] =
        module.handleErrorWith(fa)(f)
      override def monotonic: DriverIO[FiniteDuration] = module.monotonic
      override def realTime: DriverIO[FiniteDuration] = module.realtime
      override def suspend[A](hint: Sync.Type)(thunk: => A): DriverIO[A] = module.suspend(hint)(thunk)
      override def forceR[A, B](fa: DriverIO[A])(fb: DriverIO[B]): DriverIO[B] = module.forceR(fa)(fb)
      override def uncancelable[A](body: Poll[DriverIO] => DriverIO[A]): DriverIO[A] = module.uncancelable(body)
      override def canceled: DriverIO[Unit] = module.canceled
      override def onCancel[A](fa: DriverIO[A], fin: DriverIO[Unit]): DriverIO[A] = module.onCancel(fa, fin)
      override def fromFuture[A](fut: DriverIO[Future[A]]): DriverIO[A] = module.fromFuture(fut)
    }

  implicit def MonoidDriverIO[A](implicit M: Monoid[A]): Monoid[DriverIO[A]] =
    new Monoid[DriverIO[A]] {
      override val empty = monad.pure(M.empty)
      override def combine(x: DriverIO[A], y: DriverIO[A]) =
        monad.product(x, y).map { case (x, y) => M.combine(x, y) }
    }
}
