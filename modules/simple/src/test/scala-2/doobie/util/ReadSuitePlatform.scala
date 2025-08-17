// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import zio.test.assertTrue

trait ReadSuitePlatform { self: ReadSuite.type =>

  protected def platformTests = List(
    test("Read should exist for AnyVal") {
      implicit val read: Read[X] = Read.derived
      assertTrue(Read[X].length == 1)
    },
  )
}
