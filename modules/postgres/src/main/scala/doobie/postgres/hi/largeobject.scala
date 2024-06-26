// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.hi

import cats.syntax.apply.*
import doobie.postgres.PFLO
import doobie.postgres.free.largeobject.LargeObjectIO
import doobie.util.io.IOActions

import java.io.File
import java.io.InputStream
import java.io.OutputStream

object largeobject {

  val io = new IOActions[LargeObjectIO]

  def copyFromFile(blockSize: Int, file: File): LargeObjectIO[Unit] =
    PFLO.getOutputStream.flatMap { os => io.copyFileToStream(blockSize, file, os) *> io.flush(os) }

  def copyToFile(blockSize: Int, file: File): LargeObjectIO[Unit] =
    PFLO.getInputStream.flatMap { is => io.copyStreamToFile(blockSize, file, is) }

  def copyFromStream(blockSize: Int, is: InputStream): LargeObjectIO[Unit] =
    PFLO.getOutputStream.flatMap { os =>
      io.copyStream(new Array[Byte](blockSize))(is, os)
    }

  def copyToStream(blockSize: Int, os: OutputStream): LargeObjectIO[Unit] =
    PFLO.getInputStream.flatMap { is =>
      io.copyStream(new Array[Byte](blockSize))(is, os)
    }
}
