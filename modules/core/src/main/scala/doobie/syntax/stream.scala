// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.syntax

import cats.data.Kleisli
import cats.effect.kernel.Async
import cats.effect.kernel.MonadCancelThrow
import doobie.free.connection.ConnectionIO
import doobie.util.transactor.Transactor
import fs2.Pipe
import fs2.Stream

import scala.language.implicitConversions

class StreamOps[F[_], A](fa: Stream[F, A]) {
  def transact[M[_]: MonadCancelThrow](xa: Transactor[M])(implicit
    ev: Stream[F, A] =:= Stream[ConnectionIO, A],
  ): Stream[M, A] = xa.transP.apply(fa)
}
class KleisliStreamOps[A, B](fa: Stream[Kleisli[ConnectionIO, A, *], B]) {
  def transact[M[_]: MonadCancelThrow](xa: Transactor[M]): Stream[Kleisli[M, A, *], B] = xa.transPK[A].apply(fa)
}
class PipeOps[F[_], A, B](inner: Pipe[F, A, B]) {
  def transact[M[_]: Async](xa: Transactor[M])(implicit ev: Pipe[F, A, B] =:= Pipe[ConnectionIO, A, B]): Pipe[M, A, B] =
    xa.liftP(inner)
}

trait ToStreamOps {
  implicit def toDoobieStreamOps[F[_], A](fa: Stream[F, A]): StreamOps[F, A] =
    new StreamOps(fa)
  implicit def toDoobieKleisliStreamOps[A, B](fa: Stream[Kleisli[ConnectionIO, A, *], B]): KleisliStreamOps[A, B] =
    new KleisliStreamOps(fa)
  implicit def toDoobiePipeOps[F[_], A, B](inner: Pipe[F, A, B]): PipeOps[F, A, B] =
    new PipeOps(inner)
}

object stream extends ToStreamOps
