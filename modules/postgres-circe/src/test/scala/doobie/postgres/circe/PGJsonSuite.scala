// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.circe

import doobie.postgres.PostgresDatabaseSpec
import doobie.syntax.string.*
import doobie.util.Get
import doobie.util.Put
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import zio.test.Gen
import zio.test.assertTrue

object PGJsonSuite extends PostgresDatabaseSpec {
  import doobie.postgres.PostgresTypesSuite.suiteGetPut

  override val spec = suite("PGJson")(
    suite("types")(
      {
        import doobie.postgres.circe.json.implicits.*
        suiteGetPut("json", Gen.const(Json.obj("something" -> Json.fromString("Yellow"))))
      }, {
        import doobie.postgres.circe.jsonb.implicits.*
        suiteGetPut("jsonb", Gen.const(Json.obj("something" -> Json.fromString("Yellow"))))
      },
    ),
    suite("check")(
      suite("json")({
        import doobie.postgres.circe.json.implicits.*
        test("read") {
          fr"select '{}' :: json".query[Json].analysis.transact.map { a =>
            assertTrue(a.columnTypeErrors == Nil)
          }
        } ::
        test("write") {
          fr"select ${Json.obj()} :: json".query[Json].analysis.transact.map { a =>
            assertTrue(a.parameterTypeErrors == Nil)
          }
        } :: Nil
      }),
      suite("jsonb")({
        import doobie.postgres.circe.jsonb.implicits.*
        test("read") {
          fr"select '{}' :: jsonb".query[Json].analysis.transact.map { a =>
            assertTrue(a.columnTypeErrors == Nil)
          }
        } ::
        test("write") {
          fr"select ${Json.obj()} :: jsonb".query[Json].analysis.transact.map { a =>
            assertTrue(a.parameterTypeErrors == Nil)
          }
        } :: Nil
      }),
      suite("custom type")({
        test("read") {
          fr"select '{}' :: jsonb".query[Foo].analysis.transact.map { a =>
            assertTrue(a.columnTypeErrors == Nil)
          }
        } ::
        test("write") {
          fr"select ${Foo(Json.obj())} :: jsonb".query[Foo].analysis.transact.map { a =>
            assertTrue(a.parameterTypeErrors == Nil)
          }
        } :: Nil
      }),
    ),
  )

  private case class Foo(x: Json)
  private object Foo {
    import doobie.postgres.circe.jsonb.implicits.*

    implicit val fooEncoder: Encoder[Foo] = Encoder[Json].contramap(_.x)
    implicit val fooDecoder: Decoder[Foo] = Decoder[Json].map(Foo(_))
    implicit val fooGet: Get[Foo] = pgDecoderGetT[Foo]
    implicit val fooPut: Put[Foo] = pgEncoderPutT[Foo]
  }
}
