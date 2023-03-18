package doobie.refined

import doobie.h2.BaseH2DatabaseSpec
import zio.ZLayer

abstract class H2DatabaseSpec extends BaseH2DatabaseSpec {
  override val bootstrap = H2DatabaseSpec.layer
}
object H2DatabaseSpec {
  val layer = ZLayer.scoped(doobie.h2.H2DatabaseSpec.create("test_refined"))
}
