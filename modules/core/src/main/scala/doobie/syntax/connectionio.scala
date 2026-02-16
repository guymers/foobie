// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.syntax

import doobie.free.connection.ConnectionIO
import doobie.util.transactor.Transactor

import scala.language.implicitConversions

class ConnectionIOOps[A](ma: ConnectionIO[A]) {
  def transact[M[_]](xa: Transactor[M]): M[A] = xa.run(ma)
}

trait ToConnectionIOOps {
  implicit def toConnectionIOOps[A](ma: ConnectionIO[A]): ConnectionIOOps[A] =
    new ConnectionIOOps(ma)
}

object connectionio extends ToConnectionIOOps
