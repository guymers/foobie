// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie
package util

trait ReadSuitePlatform { self: munit.FunSuite =>
  import ReadSuite.X

  test("Read should exist for AnyVal") {
    assertEquals(util.Read[X].length, 1)
  }
}
