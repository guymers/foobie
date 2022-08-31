// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

object GetSuitePlatform {
  final case class Y(x: String) extends AnyVal
  final case class P(x: Int) extends AnyVal
}

trait GetSuitePlatform { self: munit.FunSuite =>
  import GetSuitePlatform.*

  test("Get can be auto derived for unary products (AnyVal)") {
    import doobie.generic.auto.*

    Get[Y]: Unit
    Get[P]: Unit
  }

  test("Get can be explicitly derived for unary products (AnyVal)") {
    Get.derived[Y]: Unit
    Get.derived[P]: Unit
  }

}
