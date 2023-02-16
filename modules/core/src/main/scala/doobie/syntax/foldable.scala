// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.syntax

import cats.Foldable
import cats.Monoid
import doobie.util.foldable as F

trait ToFoldableOps {
  implicit final class FoldableOps[F[_]: Foldable, A: Monoid](self: F[A]) {
    def foldSmash1(prefix: A, delim: A, suffix: A): A = F.foldSmash1(self)(prefix, delim, suffix)
  }
}

object foldable extends ToFoldableOps
