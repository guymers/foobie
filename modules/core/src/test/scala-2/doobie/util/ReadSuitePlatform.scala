// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie
package util

import shapeless.*
import shapeless.record.*

import scala.annotation.nowarn

trait ReadSuitePlatform { self: munit.FunSuite =>

  test("Read should exist for shapeless record types") {
    type DL = (Double, Long) // used below
    type A = Record.`'foo -> Int, 'bar -> String, 'baz -> DL, 'quz -> Woozle`.T
    util.Read[A]: Unit
    util.Read[(A, A)]: Unit
  }: @nowarn("msg=.*DL is never used.*")

  case class Woozle(a: (String, Int), b: Int :: String :: HNil, c: Boolean)

  test("Read should exist for some fancy types") {
    util.Read[Woozle]: Unit
    util.Read[(Woozle, String)]: Unit
    util.Read[(Int, Woozle :: Woozle :: String :: HNil)]: Unit
  }

  test("Read should exist for option of some fancy types") {
    util.Read[Option[Woozle]]: Unit
    util.Read[Option[(Woozle, String)]]: Unit
    util.Read[Option[(Int, Woozle :: Woozle :: String :: HNil)]]: Unit
  }

}
