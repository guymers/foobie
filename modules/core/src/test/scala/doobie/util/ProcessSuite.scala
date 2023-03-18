// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.effect.SyncIO
import doobie.util.stream.repeatEvalChunks
import zio.test.Gen
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zio.test.check

import scala.util.Random

object ProcessSuite extends ZIOSpecDefault {

  override val spec = suite("Process")(
    test("repeatEvalChunks must yield the same result irrespective of chunk size") {
      check(Gen.int(1, 1000)) { chunkSize =>
        val dataSize = 1000
        val data = Seq.fill(dataSize)(Random.nextInt())
        val fa = {
          var temp = data
          SyncIO {
            val (h, t) = temp.splitAt(chunkSize)
            temp = t
            h
          }
        }
        val result = repeatEvalChunks(fa).compile.toVector.unsafeRunSync()
        assertTrue(result == data.toVector)
      }
    },
  )

}
