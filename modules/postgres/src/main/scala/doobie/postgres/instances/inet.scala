// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.instances

import doobie.util.meta.Meta
import org.postgresql.util.PGobject

import java.net.InetAddress

object inet {

  // Network Address Types
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  implicit val InetType: Meta[InetAddress] = Meta.Advanced.other[PGobject]("inet").timap[InetAddress](o =>
    Option(o).map(a => InetAddress.getByName(a.getValue)).orNull,
  )(a =>
    Option(a).map { a =>
      val o = new PGobject
      o.setType("inet")
      o.setValue(a.getHostAddress)
      o
    }.orNull,
  )
}
