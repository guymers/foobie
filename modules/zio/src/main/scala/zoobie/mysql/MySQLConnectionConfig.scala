package zoobie.mysql

import java.util.Properties

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

    properties.foreachEntry { case (k, v) => p.setProperty(k, v) }

    p
  }
}
