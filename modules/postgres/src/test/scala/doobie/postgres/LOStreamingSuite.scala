// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import doobie.free.connection.ConnectionIO
import doobie.syntax.connectionio.*
import fs2.Chunk
import fs2.Pure
import fs2.Stream
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class LOStreamingSuite extends munit.ScalaCheckSuite {
  import PostgresTestTransactor.xa
  import cats.effect.unsafe.implicits.global

  def genFiniteStream[F[_], A: Arbitrary]: Gen[Stream[F, A]] =
    arbitrary[Vector[Vector[A]]].map { chunks =>
      chunks.map { chunk =>
        Stream.chunk(Chunk.seq(chunk))
      }.foldLeft(Stream.empty.covaryAll[F, A])(_ ++ _)
    }

  test("large object streaming should round-trip") {
    forAll(genFiniteStream[Pure, Byte]) { data =>
      val data0 = data.covary[ConnectionIO]

      val result = Stream.bracket(PHLOS.createLOFromStream(data0))(oid =>
        PHC.pgGetLargeObjectAPI(PFLOM.unlink(oid)),
      ).flatMap(oid => PHLOS.createStreamFromLO(oid, chunkSize = 1024 * 10))
        .compile.toVector.transact(xa).unsafeRunSync()

      assertEquals(result, data.toVector)
    }
  }

}
