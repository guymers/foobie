/*
 * Copyright 2017-2019 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zoobie.interop

import cats.Monad
import zio.Trace
import zio.UIO
import zio.ZIO
import zio.internal.stacktracer.InteropTracer
import zio.internal.stacktracer.Tracer as CoreTracer

/**
 * Copied from zio-interop-cats as the Scala 3 version does not have optional
 * dependencies on cats-effect and fs2.
 *
 * @see
 *   https://github.com/zio/interop-cats/blob/v23.1.0.5/zio-interop-cats/shared/src/main/scala/zio/interop/cats.scala#L563
 */
object ZioMonad extends Monad[UIO] {

  override final def pure[A](a: A) =
    ZIO.succeed(a)

  override final def map[A, B](fa: UIO[A])(f: A => B) = {
    implicit def trace: Trace = InteropTracer.newTrace(f)

    fa.map(f)
  }

  override final def flatMap[A, B](fa: UIO[A])(f: A => UIO[B]) = {
    implicit def trace: Trace = InteropTracer.newTrace(f)

    fa.flatMap(f)
  }

  override final def flatTap[A, B](fa: UIO[A])(f: A => UIO[B]) = {
    implicit def trace: Trace = InteropTracer.newTrace(f)

    fa.tap(f)
  }

  override final def widen[A, B >: A](fa: UIO[A]) =
    fa

  override final def map2[A, B, Z](fa: UIO[A], fb: UIO[B])(f: (A, B) => Z) = {
    implicit def trace: Trace = InteropTracer.newTrace(f)

    fa.zipWith(fb)(f)
  }

  override final def as[A, B](fa: UIO[A], b: B) =
    fa.as(b)(CoreTracer.newTrace)

  override final def whenA[A](cond: Boolean)(f: => UIO[A]) = {
    val byName: () => UIO[A] = () => f
    implicit def trace: Trace = InteropTracer.newTrace(byName)

    ZIO.when(cond)(f).unit
  }

  override final def unit =
    ZIO.unit

  override final def tailRecM[A, B](a: A)(f: A => UIO[Either[A, B]]) = {
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def loop(a: A): UIO[B] = f(a).flatMap {
      case Left(a) => loop(a)
      case Right(b) => ZIO.succeed(b)
    }

    ZIO.suspendSucceed(loop(a))
  }
}
