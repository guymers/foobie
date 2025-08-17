// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.data.State
import doobie.util.lens.*
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object LensSuite extends ZIOSpecDefault {

  case class Name(first: String, last: String)
  object Name {
    val first: Name @> String = Lens(_.first, (a, b) => a.copy(first = b))
    val last: Name @> String = Lens(_.last, (a, b) => a.copy(last = b))
  }

  case class Address(name: Name, street: String)
  object Address {
    val name: Address @> Name = Lens(_.name, (a, b) => a.copy(name = b))
    val street: Address @> String = Lens(_.street, (a, b) => a.copy(street = b))
    val first: Address @> String = name >=> Name.first
    val last: Address @> String = name >=> Name.last
  }

  private val bob = Address(Name("Bob", "Dole"), "123 Foo St.")

  private def exec[S](st: State[S, ?], s: S): S = st.runS(s).value

  override val spec = suite("Lens")(
    test("modify") {
      val prog: State[Address, Unit] = for {
        _ <- Address.first %= (_.toUpperCase)
        _ <- Address.last %= (_.toLowerCase)
        _ <- Address.street %= (_.replace('o', '*'))
      } yield ()
      assertTrue(exec(prog, bob) == Address(Name("BOB", "dole"), "123 F** St."))
    },
    test("set") {
      val prog: State[Address, Unit] = for {
        _ <- Address.first := "Jimmy"
        _ <- Address.last := "Carter"
        _ <- Address.street := "12 Peanut Dr."
      } yield ()
      assertTrue(exec(prog, bob) == Address(Name("Jimmy", "Carter"), "12 Peanut Dr."))
    },
  )

}
