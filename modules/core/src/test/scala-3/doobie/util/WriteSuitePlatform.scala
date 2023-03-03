// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

trait WriteSuitePlatform { self: munit.FunSuite =>

  test("derives") {
    case class Foo(a: String, b: Int) derives Write
  }

  test("does not auto derive") {
    val _ = compileErrors("""
      case class Foo(a: String, b: Int)
      case class Bar(a: String, foo: Foo) derives Write
    """)
  }
}
