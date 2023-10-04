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

import java.sql.Ref
import scala.concurrent.duration.FiniteDuration

object ref { module =>

  // Algebra of operations for Ref. Each accepts a visitor as an alternative to pattern-matching.
  sealed trait RefOp[A] {
    def visit[F[_]](v: RefOp.Visitor[F]): F[A]
  }

  // Free monad over RefOp.
  type RefIO[A] = FF[RefOp, A]

  // Module of instances and constructors of RefOp.
  @SuppressWarnings(Array("org.wartremover.warts.ArrayEquals"))
  object RefOp {

    // Given a Ref we can embed a RefIO program in any algebra that understands embedding.
    implicit val RefOpEmbeddable: Embeddable[RefOp, Ref] =
      new Embeddable[RefOp, Ref] {
        def embed[A](j: Ref, fa: FF[RefOp, A]) = Embedded.Ref(j, fa)
      }

    // Interface for a natural transformation RefOp ~> F encoded via the visitor pattern.
    // This approach is much more efficient than pattern-matching for large algebras.
    trait Visitor[F[_]] extends (RefOp ~> F) {
      final def apply[A](fa: RefOp[A]): F[A] = fa.visit(this)

      // Common
      def raw[A](f: Ref => A): F[A]
      def embed[A](e: Embedded[A]): F[A]
      def raiseError[A](e: Throwable): F[A]
      def handleErrorWith[A](fa: RefIO[A])(f: Throwable => RefIO[A]): F[A]
      def monotonic: F[FiniteDuration]
      def realTime: F[FiniteDuration]
      def delay[A](thunk: => A): F[A]
      def suspend[A](hint: Sync.Type)(thunk: => A): F[A]
      def forceR[A, B](fa: RefIO[A])(fb: RefIO[B]): F[B]
      def uncancelable[A](body: Poll[RefIO] => RefIO[A]): F[A]
      def poll[A](poll: Any, fa: RefIO[A]): F[A]
      def canceled: F[Unit]
      def onCancel[A](fa: RefIO[A], fin: RefIO[Unit]): F[A]

      // Ref
      def getBaseTypeName: F[String]
      def getObject: F[AnyRef]
      def getObject(a: java.util.Map[String, Class[?]]): F[AnyRef]
      def setObject(a: AnyRef): F[Unit]

    }

    // Common operations for all algebras.
    final case class Raw[A](f: Ref => A) extends RefOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.raw(f)
    }
    final case class Embed[A](e: Embedded[A]) extends RefOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.embed(e)
    }
    final case class RaiseError[A](e: Throwable) extends RefOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.raiseError(e)
    }
    final case class HandleErrorWith[A](fa: RefIO[A], f: Throwable => RefIO[A]) extends RefOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.handleErrorWith(fa)(f)
    }
    case object Monotonic extends RefOp[FiniteDuration] {
      def visit[F[_]](v: Visitor[F]) = v.monotonic
    }
    case object Realtime extends RefOp[FiniteDuration] {
      def visit[F[_]](v: Visitor[F]) = v.realTime
    }
    final case class Suspend[A](hint: Sync.Type, thunk: () => A) extends RefOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.suspend(hint)(thunk())
    }
    final case class ForceR[A, B](fa: RefIO[A], fb: RefIO[B]) extends RefOp[B] {
      def visit[F[_]](v: Visitor[F]) = v.forceR(fa)(fb)
    }
    final case class Uncancelable[A](body: Poll[RefIO] => RefIO[A]) extends RefOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.uncancelable(body)
    }
    final case class Poll1[A](poll: Any, fa: RefIO[A]) extends RefOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.poll(poll, fa)
    }
    case object Canceled extends RefOp[Unit] {
      def visit[F[_]](v: Visitor[F]) = v.canceled
    }
    final case class OnCancel[A](fa: RefIO[A], fin: RefIO[Unit]) extends RefOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.onCancel(fa, fin)
    }

    // Ref-specific operations.
    case object GetBaseTypeName extends RefOp[String] {
      def visit[F[_]](v: Visitor[F]) = v.getBaseTypeName
    }
    case object GetObject extends RefOp[AnyRef] {
      def visit[F[_]](v: Visitor[F]) = v.getObject
    }
    final case class GetObject1(a: java.util.Map[String, Class[?]]) extends RefOp[AnyRef] {
      def visit[F[_]](v: Visitor[F]) = v.getObject(a)
    }
    final case class SetObject(a: AnyRef) extends RefOp[Unit] {
      def visit[F[_]](v: Visitor[F]) = v.setObject(a)
    }

  }
  import RefOp.*

  // Smart constructors for operations common to all algebras.
  val unit: RefIO[Unit] = FF.pure[RefOp, Unit](())
  def pure[A](a: A): RefIO[A] = FF.pure[RefOp, A](a)
  def raw[A](f: Ref => A): RefIO[A] = FF.liftF(Raw(f))
  def embed[F[_], J, A](j: J, fa: FF[F, A])(implicit ev: Embeddable[F, J]): FF[RefOp, A] =
    FF.liftF(Embed(ev.embed(j, fa)))
  def raiseError[A](err: Throwable): RefIO[A] = FF.liftF[RefOp, A](RaiseError(err))
  def handleErrorWith[A](fa: RefIO[A])(f: Throwable => RefIO[A]): RefIO[A] = FF.liftF[RefOp, A](HandleErrorWith(fa, f))
  val monotonic = FF.liftF[RefOp, FiniteDuration](Monotonic)
  val realtime = FF.liftF[RefOp, FiniteDuration](Realtime)
  def delay[A](thunk: => A) = FF.liftF[RefOp, A](Suspend(Sync.Type.Delay, () => thunk))
  def suspend[A](hint: Sync.Type)(thunk: => A) = FF.liftF[RefOp, A](Suspend(hint, () => thunk))
  def forceR[A, B](fa: RefIO[A])(fb: RefIO[B]) = FF.liftF[RefOp, B](ForceR(fa, fb))
  def uncancelable[A](body: Poll[RefIO] => RefIO[A]) = FF.liftF[RefOp, A](Uncancelable(body))
  def capturePoll[M[_]](mpoll: Poll[M]) = new Poll[RefIO] {
    def apply[A](fa: RefIO[A]) = FF.liftF[RefOp, A](Poll1(mpoll, fa))
  }
  val canceled = FF.liftF[RefOp, Unit](Canceled)
  def onCancel[A](fa: RefIO[A], fin: RefIO[Unit]) = FF.liftF[RefOp, A](OnCancel(fa, fin))

  // Smart constructors for Ref-specific operations.
  val getBaseTypeName: RefIO[String] = FF.liftF(GetBaseTypeName)
  val getObject: RefIO[AnyRef] = FF.liftF(GetObject)
  def getObject(a: java.util.Map[String, Class[?]]): RefIO[AnyRef] = FF.liftF(GetObject1(a))
  def setObject(a: AnyRef): RefIO[Unit] = FF.liftF(SetObject(a))

  private val monad = FF.catsFreeMonadForFree[RefOp]

  // Typeclass instances for RefIO
  implicit val SyncRefIO: Sync[RefIO] =
    new Sync[RefIO] {
      override val rootCancelScope = CancelScope.Cancelable
      override def pure[A](x: A): RefIO[A] = monad.pure(x)
      override def map[A, B](fa: RefIO[A])(f: A => B) = monad.map(fa)(f)
      override def flatMap[A, B](fa: RefIO[A])(f: A => RefIO[B]): RefIO[B] = monad.flatMap(fa)(f)
      override def tailRecM[A, B](a: A)(f: A => RefIO[Either[A, B]]): RefIO[B] = monad.tailRecM(a)(f)
      override def raiseError[A](e: Throwable): RefIO[A] = module.raiseError(e)
      override def handleErrorWith[A](fa: RefIO[A])(f: Throwable => RefIO[A]): RefIO[A] = module.handleErrorWith(fa)(f)
      override def monotonic: RefIO[FiniteDuration] = module.monotonic
      override def realTime: RefIO[FiniteDuration] = module.realtime
      override def suspend[A](hint: Sync.Type)(thunk: => A): RefIO[A] = module.suspend(hint)(thunk)
      override def forceR[A, B](fa: RefIO[A])(fb: RefIO[B]): RefIO[B] = module.forceR(fa)(fb)
      override def uncancelable[A](body: Poll[RefIO] => RefIO[A]): RefIO[A] = module.uncancelable(body)
      override def canceled: RefIO[Unit] = module.canceled
      override def onCancel[A](fa: RefIO[A], fin: RefIO[Unit]): RefIO[A] = module.onCancel(fa, fin)
    }

  implicit def MonoidRefIO[A](implicit M: Monoid[A]): Monoid[RefIO[A]] =
    new Monoid[RefIO[A]] {
      override val empty = monad.pure(M.empty)
      override def combine(x: RefIO[A], y: RefIO[A]) =
        monad.product(x, y).map { case (x, y) => M.combine(x, y) }
    }
}
