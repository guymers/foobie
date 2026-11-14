package zoobie.mysql

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object MySQLConnectionConfigSpec extends ZIOSpecDefault {

  private val config = MySQLConnectionConfig(
    host = "localhost",
    database = "db",
    username = "user",
    password = "password",
  )

  override val spec = suite("MySQLConnectionConfig")(
    test("uses local transaction state by default") {
      assertTrue(config.props.getProperty("useLocalTransactionState") == "true")
    },
    test("supplied properties take precedence over the defaults") {
      val props = config.copy(properties = Map("useLocalTransactionState" -> "false")).props
      assertTrue(props.getProperty("useLocalTransactionState") == "false")
    },
  )
}
