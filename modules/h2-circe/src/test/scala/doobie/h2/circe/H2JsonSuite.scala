// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.h2.circe

import doobie.h2.H2DatabaseSpec
import doobie.syntax.string.*
import doobie.util.Get
import doobie.util.Put
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import zio.test.Gen
import zio.test.assertTrue

object H2JsonSuite extends H2DatabaseSpec {
  import doobie.h2.H2TypesSpec.suiteGetPut
  import doobie.h2.circe.json.implicits.*

  override val spec = suite("H2Json")(
    {
      suiteGetPut("json", Gen.const(Json.obj("something" -> Json.fromString("Yellow"))))
    },
    test("json should check ok for read") {
      sql"SELECT '{}' FORMAT JSON".query[Json].analysis.transact.map { a =>
        assertTrue(a.columnTypeErrors == Nil)
      }
    },
    test("json should check ok for write") {
      sql"SELECT ${Json.obj()} FORMAT JSON".query[Json].analysis.transact.map { a =>
        assertTrue(a.parameterTypeErrors == Nil)
      }
    },
    test("fooGet should check ok for read") {
      sql"SELECT '{}' FORMAT JSON".query[Foo].analysis.transact.map { a =>
        assertTrue(a.columnTypeErrors == Nil)
      }
    },
    test("fooPut check ok for write") {
      sql"SELECT ${Foo(Json.obj())} FORMAT JSON".query[Foo].analysis.transact.map { a =>
        assertTrue(a.parameterTypeErrors == Nil)
      }
    },
  )

  // Encoder / Decoders
  private case class Foo(x: Json)
  private object Foo {
    implicit val fooEncoder: Encoder[Foo] = Encoder[Json].contramap(_.x)
    implicit val fooDecoder: Decoder[Foo] = Decoder[Json].map(Foo(_))
    implicit val fooGet: Get[Foo] = h2DecoderGetT[Foo]
    implicit val fooPut: Put[Foo] = h2EncoderPutT[Foo]
  }

}
