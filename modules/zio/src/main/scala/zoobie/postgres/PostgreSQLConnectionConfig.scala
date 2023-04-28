package zoobie.postgres

import java.util.Properties

final case class PostgreSQLConnectionConfig(
  host: String,
  port: Int = 5432,
  database: String,
  username: String,
  password: String,
  applicationName: String,
  readOnly: Boolean = false,
  properties: Map[String, String] = Map.empty,
) {
  val url = s"jdbc:postgresql://$host:${port.toString}/$database"

  def props: Properties = {
    val p = new java.util.Properties
    org.postgresql.PGProperty.PG_HOST.set(p, host)
    org.postgresql.PGProperty.PG_PORT.set(p, port)
    org.postgresql.PGProperty.PG_DBNAME.set(p, database)
    org.postgresql.PGProperty.USER.set(p, username)
    org.postgresql.PGProperty.PASSWORD.set(p, password)
    org.postgresql.PGProperty.APPLICATION_NAME.set(p, applicationName)

    if (readOnly) {
      org.postgresql.PGProperty.READ_ONLY.set(p, true)
      org.postgresql.PGProperty.READ_ONLY_MODE.set(p, "always")
    }

    properties.foreachEntry { case (k, v) => p.setProperty(k, v) }

    p
  }
}
