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
    test("uses a 10 second connect timeout by default") {
      assertTrue(config.props.getProperty("connectTimeout") == "10000")
    },
    test("supplied properties take precedence over the defaults") {
      val props = config.copy(properties = Map("useLocalTransactionState" -> "false", "connectTimeout" -> "5000")).props
      assertTrue(props.getProperty("useLocalTransactionState") == "false") &&
      assertTrue(props.getProperty("connectTimeout") == "5000")
    },
  )
}
