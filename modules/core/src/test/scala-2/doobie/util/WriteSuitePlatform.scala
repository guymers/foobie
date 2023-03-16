// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import zio.test.assertTrue

trait WriteSuitePlatform { self: WriteSuite.type =>

  protected def platformTests = List(
    test("exist for AnyVal") {
      import doobie.util.Write.Auto.*

      assertTrue(Write[X].length == 1)
    },
  )
}
