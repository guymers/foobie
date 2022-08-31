// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import shapeless.*
import shapeless.record.*

import scala.annotation.nowarn

trait ReadSuitePlatform { self: munit.FunSuite =>
  import doobie.generic.auto.*

  test("Read should exist for shapeless record types") {
    type DL = (Double, Long) // used below
    type A = Record.`'foo -> Int, 'bar -> String, 'baz -> DL, 'quz -> Woozle`.T
    Read[A]: Unit
    Read[(A, A)]: Unit
  }: @nowarn("msg=.*DL is never used.*")

  case class Woozle(a: (String, Int), b: Int :: String :: HNil, c: Boolean)

  test("Read should exist for some fancy types") {
    Read[Woozle]: Unit
    Read[(Woozle, String)]: Unit
    Read[(Int, Woozle :: Woozle :: String :: HNil)]: Unit
  }

  test("Read should exist for option of some fancy types") {
    Read[Option[Woozle]]: Unit
    Read[Option[(Woozle, String)]]: Unit
    Read[Option[(Int, Woozle :: Woozle :: String :: HNil)]]: Unit
  }

}
