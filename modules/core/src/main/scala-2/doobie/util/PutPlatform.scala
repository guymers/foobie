// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import shapeless.*
import shapeless.ops.hlist.IsHCons

trait PutPlatform {

  /** @group Instances */
  implicit def unaryProductPut[A, L <: HList, H, T <: HList](implicit
    G: Generic.Aux[A, L],
    C: IsHCons.Aux[L, H, T],
    H: => Put[H],
    E: (H :: HNil) =:= L,
  ): MkPut[A] = {
    void(E) // E is a necessary constraint but isn't used directly
    val put = H.contramap[A](a => G.to(a).head)
    MkPut.lift(put)
  }

}
