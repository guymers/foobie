// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.hi

import cats.syntax.all.*
import doobie.postgres.PFLO
import doobie.postgres.PFLOM
import doobie.postgres.free.largeobject.LargeObjectIO
import doobie.postgres.free.largeobjectmanager.LargeObjectManagerIO

import java.io.File
import java.io.InputStream
import java.io.OutputStream

object largeobjectmanager {

  val createLO: LargeObjectManagerIO[Long] =
    PFLOM.createLO

  @SuppressWarnings(Array("org.wartremover.warts.Overloading"))
  def createLO(a: Int): LargeObjectManagerIO[Long] =
    PFLOM.createLO(a)

  def delete(a: Long): LargeObjectManagerIO[Unit] =
    PFLOM.delete(a)

  @SuppressWarnings(Array("org.wartremover.warts.Overloading"))
  def open[A](a: Long, b: Int)(k: LargeObjectIO[A]): LargeObjectManagerIO[A] =
    PFLOM.open(a, b) >>= (PFLOM.embed(_, k <* PFLO.close))

  @SuppressWarnings(Array("org.wartremover.warts.Overloading"))
  def open[A](a: Long)(k: LargeObjectIO[A]): LargeObjectManagerIO[A] =
    PFLOM.open(a) >>= (PFLOM.embed(_, k <* PFLO.close))

  def unlink(a: Long): LargeObjectManagerIO[Unit] =
    PFLOM.unlink(a)

  def createLOFromFile(blockSize: Int, file: File): LargeObjectManagerIO[Long] =
    createLO >>= { oid => open(oid)(largeobject.copyFromFile(blockSize, file)).as(oid) }

  def createFileFromLO(blockSize: Int, oid: Long, file: File): LargeObjectManagerIO[Unit] =
    open(oid)(largeobject.copyToFile(blockSize, file))

  def createLOFromStream(blockSize: Int, is: InputStream): LargeObjectManagerIO[Long] =
    largeobjectmanager.createLO >>= { oid =>
      largeobjectmanager.open(oid)(largeobject.copyFromStream(blockSize, is)).as(oid)
    }

  def createStreamFromLO(blockSize: Int, oid: Long, os: OutputStream): LargeObjectManagerIO[Unit] =
    open(oid)(largeobject.copyToStream(blockSize, os))
}
