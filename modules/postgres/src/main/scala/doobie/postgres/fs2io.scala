/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package doobie.postgres

import cats.effect.kernel.Sync
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import fs2.Chunk
import fs2.Pipe
import fs2.Stream

import java.io.InputStream
import java.io.OutputStream

// Inlined from FS2 to avoid depending on fs2-io
// https://github.com/typelevel/fs2/blob/v3.9.1/io/shared/src/main/scala/fs2/io/io.scala
object fs2io {

  def readInputStream[F[_]](
    fis: F[InputStream],
    chunkSize: Int,
    closeAfterUse: Boolean = true,
  )(implicit F: Sync[F]): Stream[F, Byte] = {

    def read(is: InputStream, buf: Array[Byte]) = {
      F.blocking(is.read(buf)).map { numBytes =>
        if (numBytes < 0) None
        else if (numBytes == 0) Some(Chunk.empty)
        else if (numBytes < buf.length) Some(Chunk.array(buf, 0, numBytes))
        else Some(Chunk.array(buf))
      }
    }

    val buf = F.delay(new Array[Byte](chunkSize))

    def useIs(is: InputStream) =
      Stream
        .eval(buf.flatMap(read(is, _)))
        .repeat
        .unNoneTerminate
        .flatMap(Stream.chunk(_))

    withResource(fis, closeAfterUse).flatMap(useIs)
  }

  def writeOutputStream[F[_]](
    fos: F[OutputStream],
    closeAfterUse: Boolean = true,
  )(implicit F: Sync[F]): Pipe[F, Byte, Nothing] = s => {

    def write(os: OutputStream, b: Array[Byte], off: Int, len: Int) = {
      F.interruptible {
        os.write(b, off, len)
        os.flush()
      }
    }

    def useOs(os: OutputStream): Stream[F, Nothing] =
      s.chunks.foreach { c =>
        val Chunk.ArraySlice(b, off, len) = c.toArraySlice[Byte]
        write(os, b, off, len)
      }

    withResource(fos, closeAfterUse).flatMap(os => useOs(os) ++ Stream.exec(F.blocking(os.flush())))
  }

  private def withResource[F[_], V <: AutoCloseable](fv: F[V], closeAfterUse: Boolean)(implicit F: Sync[F]) = {
    if (closeAfterUse) {
      Stream.bracket(fv)(v => F.blocking(v.close()))
    } else Stream.eval(fv)
  }
}
