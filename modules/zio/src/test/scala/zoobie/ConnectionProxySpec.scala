package zoobie

import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zoobie.stub.StubConnection

@SuppressWarnings(Array("org.wartremover.warts.Null"))
object ConnectionProxySpec extends ZIOSpecDefault {

  override val spec = suite("ConnectionProxy")(
    test("resets state on close") {
      val c = new ConnectionProxy(new StubConnection(_ => true))
      c.setCatalog("a catalog")
      c.setSchema("a schema")
      c.setAutoCommit(false)
      c.setReadOnly(true)
      c.setTransactionIsolation(5)

      val initial =
        (
          c.getCatalog,
          c.getSchema,
          c.getAutoCommit,
          c.isReadOnly,
          c.getTransactionIsolation,
        )

      c.close()

      val after =
        (
          c.getCatalog,
          c.getSchema,
          c.getAutoCommit,
          c.isReadOnly,
          c.getTransactionIsolation,
        )

      assertTrue(initial == ("a catalog", "a schema", false, true, 5)) &&
      assertTrue(c.isClosed) &&
      assertTrue(after == (null, null, true, false, 0))
    },
  )
}
