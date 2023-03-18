// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres

import cats.syntax.apply.*
import cats.syntax.flatMap.*
import zio.ZIO
import zio.test.assertTrue

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files

object LOSuite extends PostgresDatabaseSpec {

  // A big file. Contents are irrelevant.
  private val in = new File("init/postgres/test-db.sql")

  override val spec = suite("LO")(
    test("large object support should allow round-trip from file to large object and back") {
      for {
        out <- tempFile
        prog = PHLOM.createLOFromFile(1024 * 16, in) >>= { oid =>
          PHLOM.createFileFromLO(1024 * 16, oid, out) *> PHLOM.delete(oid)
        }
        _ <- PHC.pgGetLargeObjectAPI(prog).transact
        eq <- filesEqual(in, out)
      } yield {
        assertTrue(eq)
      }
    },
    test("large object support should allow round-trip from stream to large object and back") {
      for {
        out <- tempFile
        is <- ZIO.acquireRelease(
          ZIO.attemptBlocking(new FileInputStream(in)),
        )(is => ZIO.attemptBlocking(is.close()).ignoreLogged)
        os <- ZIO.acquireRelease(
          ZIO.attemptBlocking(new FileOutputStream(out)),
        )(os => ZIO.attemptBlocking(os.close()).ignoreLogged)
        prog = PHLOM.createLOFromStream(1024 * 16, is) >>= { oid =>
          PHLOM.createStreamFromLO(1024 * 16, oid, os) *> PHLOM.delete(oid)
        }
        _ <- PHC.pgGetLargeObjectAPI(prog).transact
        eq <- filesEqual(in, out)
      } yield {
        assertTrue(eq)
      }
    },
  )

  private def tempFile = {
    ZIO.acquireRelease(
      ZIO.attemptBlocking(File.createTempFile("doobie", "tst")),
    )(file => ZIO.attemptBlocking(file.delete()).ignoreLogged)
  }

  private def filesEqual(f1: File, f2: File) = {
    ZIO.attemptBlocking {
      Files.readAllBytes(f1.toPath) sameElements Files.readAllBytes(f2.toPath)
    }
  }
}
