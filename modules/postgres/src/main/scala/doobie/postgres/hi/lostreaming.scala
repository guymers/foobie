// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.hi

import cats.syntax.functor.*
import doobie.ConnectionIO
import doobie.postgres.PFLO
import doobie.postgres.PFLOM
import fs2.Stream
import org.postgresql.largeobject.LargeObject

import java.io.InputStream
import java.io.OutputStream

object lostreaming {

  def createLOFromStream(data: Stream[ConnectionIO, Byte]): ConnectionIO[Long] =
    createLO.flatMap { oid =>
      Stream.bracket(openLO(oid))(closeLO)
        .flatMap(lo => data.through(doobie.postgres.fs2io.writeOutputStream(getOutputStream(lo))))
        .compile.drain.as(oid)
    }

  def createStreamFromLO(oid: Long, chunkSize: Int): Stream[ConnectionIO, Byte] =
    Stream.bracket(openLO(oid))(closeLO)
      .flatMap(lo => doobie.postgres.fs2io.readInputStream(getInputStream(lo), chunkSize))

  private val createLO: ConnectionIO[Long] =
    connection.pgGetLargeObjectAPI(PFLOM.createLO)

  private def openLO(oid: Long): ConnectionIO[LargeObject] =
    connection.pgGetLargeObjectAPI(PFLOM.open(oid))

  private def closeLO(lo: LargeObject): ConnectionIO[Unit] =
    connection.pgGetLargeObjectAPI(PFLOM.embed(lo, PFLO.close))

  private def getOutputStream(lo: LargeObject): ConnectionIO[OutputStream] =
    connection.pgGetLargeObjectAPI(PFLOM.embed(lo, PFLO.getOutputStream))

  private def getInputStream(lo: LargeObject): ConnectionIO[InputStream] =
    connection.pgGetLargeObjectAPI(PFLOM.embed(lo, PFLO.getInputStream))
}
