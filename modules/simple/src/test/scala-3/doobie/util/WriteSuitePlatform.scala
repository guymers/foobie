// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import doobie.test.illTyped
import zio.test.assertCompletes

trait WriteSuitePlatform { self: WriteSuite.type =>

  protected def platformTests = List(
    test("derives") {
      case class Foo(a: String, b: Int) derives Write
      assertCompletes
    },
    test("does not auto derive") {
      val _ = illTyped("""
        case class Foo(a: String, b: Int)
        case class Bar(a: String, foo: Foo) derives Write
      """)
      assertCompletes
    },
  )
}
