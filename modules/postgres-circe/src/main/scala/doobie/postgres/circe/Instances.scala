// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.circe

import cats.syntax.either.*
import cats.syntax.show.*
import doobie.Get
import doobie.Put
import io.circe.*
import io.circe.jawn.*
import io.circe.syntax.*

object Instances {

  trait JsonbInstances {
    implicit val jsonbPut: Put[Json] =
      doobie.postgres.instances.json.jsonbPutFromString(_.noSpaces)

    implicit val jsonbGet: Get[Json] =
      doobie.postgres.instances.json.jsonbGetFromString(parse(_).leftMap(_.show))

    def pgEncoderPutT[A: Encoder]: Put[A] =
      Put[Json].tcontramap(_.asJson)

    def pgEncoderPut[A: Encoder]: Put[A] =
      Put[Json].contramap(_.asJson)

    def pgDecoderGetT[A: Decoder]: Get[A] =
      Get[Json].temap(json => json.as[A].leftMap(_.show))

    @SuppressWarnings(Array("org.wartremover.warts.Throw"))
    def pgDecoderGet[A: Decoder]: Get[A] =
      Get[Json].map(json => json.as[A].fold(throw _, identity))
  }

  trait JsonInstances {
    implicit val jsonPut: Put[Json] =
      doobie.postgres.instances.json.jsonPutFromString(_.noSpaces)

    implicit val jsonGet: Get[Json] =
      doobie.postgres.instances.json.jsonGetFromString(parse(_).leftMap(_.show))

    def pgEncoderPutT[A: Encoder]: Put[A] =
      Put[Json].tcontramap(_.asJson)

    def pgEncoderPut[A: Encoder]: Put[A] =
      Put[Json].contramap(_.asJson)

    def pgDecoderGetT[A: Decoder]: Get[A] =
      Get[Json].temap(json => json.as[A].leftMap(_.show))

    @SuppressWarnings(Array("org.wartremover.warts.Throw"))
    def pgDecoderGet[A: Decoder]: Get[A] =
      Get[Json].map(json => json.as[A].fold(throw _, identity))

  }

}
