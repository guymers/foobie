// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

trait WriteSuitePlatform { self: munit.FunSuite =>
  import WriteSuite.X

  test("Write should exist for AnyVal") {
    import doobie.util.Write.Auto.*

    assertEquals(Write[X].length, 1)
  }
}
