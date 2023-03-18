// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import doobie.free.connection.ConnectionIO
import fs2.Chunk
import fs2.Pure
import fs2.Stream
import zio.test.Gen
import zio.test.assertTrue
import zio.test.check

import java.nio.charset.StandardCharsets

object LOStreamingSuite extends PostgresDatabaseSpec {

  override val spec = suite("LOStreaming")(
    test("large object streaming should round-trip") {
      val genString = Gen.bounded(2048, 10480)(i => Gen.fromRandom(_.nextString(i)))
      val gen = Gen.vectorOfBounded(10, 30)(genString).map { chunks =>
        chunks.map { str =>
          Stream.chunk(Chunk.array(str.getBytes(StandardCharsets.UTF_8)))
        }.foldLeft(Stream.empty.covaryAll[Pure, Byte])(_ ++ _)
      }
      check(gen) { data =>
        val stream = data.covary[ConnectionIO]

        Stream.bracket(
          PHLOS.createLOFromStream(stream),
        )(oid => PHC.pgGetLargeObjectAPI(PFLOM.unlink(oid)))
          .flatMap(oid => PHLOS.createStreamFromLO(oid, chunkSize = 1024 * 5))
          .compile.toVector.transact
          .map { result =>
            assertTrue(result == data.toVector)
          }
      }
    },
  )
}
