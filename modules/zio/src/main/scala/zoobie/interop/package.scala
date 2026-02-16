package zoobie

import cats.Monad
import zio.ZIO

package object interop {

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  implicit def monadCats[R, E]: Monad[ZIO[R, E, *]] =
    zoobie.interop.ZioMonad.asInstanceOf[Monad[ZIO[R, E, *]]]
}
