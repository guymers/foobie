// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import cats.syntax.functor.*
import doobie.free.connection.ConnectionIO
import doobie.postgres.instances.array.*
import doobie.syntax.string.*
import doobie.util.Read
import org.postgresql.copy.CopyManager
import org.postgresql.jdbc.PgConnection
import zio.ZIO
import zio.interop.catz.core.*
import zio.stream.ZStream
import zio.test.Gen
import zio.test.assertTrue
import zio.test.check
import zoobie.ConnectionProxy
import zoobie.Transactor

import java.nio.charset.StandardCharsets

object TextSuite extends PostgresDatabaseSpec {

  private val create = fr"""
    CREATE TEMPORARY TABLE test (
      id serial, -- just for ordering
      a text,    -- String
      b int2,    -- Short
      c int4,    -- Int
      d int8,    -- Long
      e float4,  -- Float
      f float8,  -- Double
      g numeric, -- BigDecimal
      h boolean, -- Boolean
      i bytea,   -- List[Byte]
      j _text,   -- List[String]
      k _int4    -- List[Int]
    ) ON COMMIT DROP
  """.update.run.void

  private val insert = fr"COPY test (a, b, c, d, e, f, g, h, i, j, k) FROM STDIN"

  private val selectAll = fr"SELECT a, b, c, d, e, f, g, h, i, j, k FROM test ORDER BY id ASC".query[Row].to[List]

  private val genRows = Gen.listOfBounded(0, 100)(Row.gen)

  override val spec = suite("Text")(
    suite("copyIn")(
      test("insert batches of rows") {
        check(genRows) { rs =>
          ZIO.scoped(for {
            transactor <- ZIO.service[Transactor]
            conn <- transactor.connection
            interpret = transactor.interpret(conn)
            _ <- interpret(ConnectionIO.setAutoCommit(false))
            _ <- interpret(create)

            pgConn = conn.asInstanceOf[ConnectionProxy].connection.asInstanceOf[PgConnection]
            copyIn <- ZIO.acquireRelease(ZIO.succeed {
              val copy = new CopyManager(pgConn)
              copy.copyIn(insert.update.sql)
            })(copyIn => {
              ZIO.attemptBlocking {
                if (copyIn.isActive) {
                  copyIn.cancelCopy()
                }
              }.ignoreLogged
            })
            _ <- ZStream.fromIterable(rs)
              .grouped(200)
              .mapZIO { rows =>
                ZIO.attemptBlocking {
                  val str = Text.foldToString(rows)
                  val bytes = pgConn.getEncoding.encode(str)
                  copyIn.writeToCopy(bytes, 0, bytes.length)
                }
              }
              .runDrain
            _ <- ZIO.attemptBlocking(copyIn.endCopy()).ignoreLogged

            results <- interpret(selectAll)
            _ <- interpret(ConnectionIO.commit)
          } yield {
            assertTrue(results == rs)
          })
        }
      },
    ),
  )

  // A test type to insert, all optional so we can check NULL
  final case class Row(
    a: Option[String],
    b: Option[Short],
    c: Option[Int],
    d: Option[Long],
    e: Option[Float],
    f: Option[Double],
    g: Option[BigDecimal],
    h: Option[Boolean],
    i: Option[List[Byte]],
    j: Option[List[String]],
    k: Option[List[Int]],
  )
  object Row {
    implicit val read: Read[Row] = Read.derived

    private val genString = Gen.bounded(0, 2048)(i => Gen.fromRandom(_.nextString(i))).map { str =>
      // filter chars pg can't cope with
      str.replace("\u0000", "") // NUL
        .map(c => if (Character.isSpaceChar(c)) ' ' else c) // high space
        .filterNot(c => c >= 0x0e && c <= 0x1f) // low ctrl
    }

    val gen = for {
      a <- Gen.option(genString)
      b <- Gen.option(Gen.short)
      c <- Gen.option(Gen.int)
      d <- Gen.option(Gen.long)
      e <- Gen.option(Gen.float)
      f <- Gen.option(Gen.double)
      g <- Gen.option(Gen.bigDecimal(BigDecimal(Long.MinValue), BigDecimal(Long.MaxValue)))
      h <- Gen.option(Gen.boolean)
      i <- Gen.option(genString.map(_.getBytes(StandardCharsets.UTF_8).toList))
      j <- Gen.option(Gen.listOfBounded(0, 10)(genString))
      k <- Gen.option(Gen.listOfBounded(0, 10)(Gen.int))
    } yield Row(a, b, c, d, e, f, g, h, i, j, k)
  }

}
