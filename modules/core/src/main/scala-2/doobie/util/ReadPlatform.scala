// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import shapeless.::
import shapeless.<:!<
import shapeless.Generic
import shapeless.HList
import shapeless.HNil
import shapeless.OrElse
import shapeless.labelled.FieldType
import shapeless.labelled.field

trait ReadPlatform extends LowerPriorityRead {

  implicit def recordRead[K <: Symbol, H, T <: HList](implicit
    H: => Read[H] OrElse MkRead[H],
    T: => MkRead[T],
  ): MkRead[FieldType[K, H] :: T] = {
    val head = H.unify

    new MkRead[FieldType[K, H] :: T](
      head.gets ++ T.gets,
      (rs, n) => field[K](head.unsafeGet(rs, n)) :: T.unsafeGet(rs, n + head.length),
    )
  }

}

trait LowerPriorityRead extends EvenLower {

  implicit def product[H, T <: HList](implicit
    H: => Read[H] OrElse MkRead[H],
    T: => MkRead[T],
  ): MkRead[H :: T] = {
    val head = H.unify

    new MkRead[H :: T](
      head.gets ++ T.gets,
      (rs, n) => head.unsafeGet(rs, n) :: T.unsafeGet(rs, n + head.length),
    )
  }

  implicit val emptyProduct: MkRead[HNil] =
    new MkRead[HNil](Nil, (_, _) => HNil)

  implicit def generic[F, G](implicit gen: Generic.Aux[F, G], G: => MkRead[G]): MkRead[F] =
    new MkRead[F](G.gets, (rs, n) => gen.from(G.unsafeGet(rs, n)))

}

trait EvenLower {

  implicit val ohnil: MkRead[Option[HNil]] =
    new MkRead[Option[HNil]](Nil, (_, _) => Some(HNil))

  implicit def ohcons1[H, T <: HList](implicit
    H: => Read[Option[H]] OrElse MkRead[Option[H]],
    T: => MkRead[Option[T]],
    N: H <:!< Option[α] forSome { type α },
  ): MkRead[Option[H :: T]] = {
    void(N)
    val head = H.unify

    new MkRead[Option[H :: T]](
      head.gets ++ T.gets,
      (rs, n) =>
        for {
          h <- head.unsafeGet(rs, n)
          t <- T.unsafeGet(rs, n + head.length)
        } yield h :: t,
    )
  }

  implicit def ohcons2[H, T <: HList](implicit
    H: => Read[Option[H]] OrElse MkRead[Option[H]],
    T: => MkRead[Option[T]],
  ): MkRead[Option[Option[H] :: T]] = {
    val head = H.unify

    new MkRead[Option[Option[H] :: T]](
      head.gets ++ T.gets,
      (rs, n) => T.unsafeGet(rs, n + head.length).map(head.unsafeGet(rs, n) :: _),
    )
  }

  implicit def ogeneric[A, Repr <: HList](implicit
    G: Generic.Aux[A, Repr],
    B: => MkRead[Option[Repr]],
  ): MkRead[Option[A]] =
    new MkRead[Option[A]](B.gets, B.unsafeGet(_, _).map(G.from))

}
