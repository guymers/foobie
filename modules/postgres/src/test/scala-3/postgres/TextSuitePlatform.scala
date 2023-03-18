package doobie.postgres

import zio.test.ZIOSpecDefault
import zio.test.assertCompletes

object TextDerivationSuite extends ZIOSpecDefault {

  override val spec = suite("Text")(
    test("derives") {
      case class Foo(a: String, b: Int) derives Text
      assertCompletes
    },
  )

}
