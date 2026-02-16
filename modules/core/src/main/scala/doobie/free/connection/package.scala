package doobie.free

import cats.free.Free

package object connection {

  type ConnectionIO[A] = Free[ConnectionOp, A]
}
