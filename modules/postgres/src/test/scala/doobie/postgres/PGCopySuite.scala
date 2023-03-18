// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import cats.effect.kernel.Sync
import cats.syntax.applicative.*
import cats.syntax.show.*
import doobie.free.connection.ConnectionIO
import doobie.postgres.syntax.fragment.*
import doobie.syntax.string.*
import doobie.util.fragment.Fragment
import fs2.Stream
import zio.ZIO
import zio.test.Live
import zio.test.assertTrue

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object PGCopySuite extends PostgresDatabaseSpec {

  private val minChunkSize = 200

  override val spec = suite("PGCopy")(
    test("copy out should read csv in utf-8 and match expectations") {

      val query = """
        copy (
          select code, name, population
          from country
          where name like 'U%'
          order by code
        ) to stdout (encoding 'utf-8', format csv)
      """

      val prog = for {
        out <- Sync[ConnectionIO].delay(new ByteArrayOutputStream)
        _ <- PHC.pgGetCopyAPI(PFCM.copyOut(query, out))
      } yield new String(out.toByteArray, StandardCharsets.UTF_8)

      prog.transact.map { result =>
        val expected = """
          |ARE,United Arab Emirates,2441000
          |GBR,United Kingdom,59623400
          |UGA,Uganda,21778000
          |UKR,Ukraine,50456000
          |UMI,United States Minor Outlying Islands,0
          |URY,Uruguay,3337000
          |USA,United States,278357000
          |UZB,Uzbekistan,24318000
          |
        """.trim.stripMargin
        assertTrue(result == expected)
      }
    },
    test("A stream with a Pure effect inserts items properly") {
      for {
        table <- createTable
        // A pure stream is fine - can copy many items
        count = 10000
        stream = Stream.emits(1 to count)
        _ <- fr"COPY $table(data) FROM STDIN".copyIn(stream, minChunkSize).transact
        queryCount <- fr"SELECT count(*) FROM $table".query[Int].unique.transact
      } yield {
        assertTrue(queryCount == count)
      }
    },
    test("A stream with a ConnectionIO effect copies <= than minChunkSize items") {
      for {
        table <- createTable
        // Can copy up to minChunkSize just fine with ConnectionIO
        inputs = 1 to minChunkSize
        stream = Stream.emits[ConnectionIO, Int](inputs).evalMap(i => (i + 2).pure[ConnectionIO])
        copiedRows <- fr"COPY $table(data) FROM STDIN".copyIn(stream, minChunkSize).transact
        queryCount <- fr"SELECT count(*) FROM $table".query[Int].unique.transact
      } yield {
        assertTrue(copiedRows == inputs.size.toLong) &&
        assertTrue(queryCount == minChunkSize)
      }
    },
    test("A stream with a ConnectionIO effect copies items with count > minChunkSize") {
      for {
        table <- createTable
        inputs = 1 to minChunkSize + 1
        stream = Stream.emits[ConnectionIO, Int](inputs).evalMap(i => (i + 2).pure[ConnectionIO])
        copiedRows <- fr"COPY $table(data) FROM STDIN".copyIn(stream, minChunkSize).transact
        queryCount <- fr"SELECT count(*) FROM $table".query[Int].unique.transact
      } yield {
        assertTrue(copiedRows == inputs.size.toLong) &&
        assertTrue(queryCount == minChunkSize + 1)
      }
    },
  )

  private def createTable = for {
    uuid <- Live.live(zio.Random.nextUUID)
    table = Fragment.const(show"copy_${uuid.toString.replaceAll("-", "")}")
    create = fr"CREATE UNLOGGED TABLE $table(id BIGSERIAL PRIMARY KEY, data BIGINT NOT NULL)".update.run
    drop = fr"DROP TABLE IF EXISTS $table".update.run
    _ <- ZIO.acquireRelease(create.transact)(_ => drop.transact.ignoreLogged)
  } yield table
}
