package zoobie.mysql

import java.util.Properties

/**
 * @param properties
 *   additional driver properties, these take precedence over the defaults set
 *   here
 */
final case class MySQLConnectionConfig(
  host: String,
  port: Int = 3306,
  database: String,
  username: String,
  password: String,
  properties: Map[String, String] = Map.empty,
) {
  val url = s"jdbc:mysql://$host:${port.toString}/$database"

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def props: Properties = {
    val p = new java.util.Properties
    p.setProperty("user", username)
    p.setProperty("password", password)

    // By default, the driver sends every commit() and rollback() to the server. With this set it
    // uses the transaction state the server reports and skips them when no transaction is open.
    // ConnectionProxy rolls back every connection returned to the pool with auto-commit off, so
    // this avoids a round trip on return when that connection has no open transaction.
    p.setProperty("useLocalTransactionState", "true")

    properties.foreachEntry { case (k, v) => p.setProperty(k, v) }

    p
  }
}
