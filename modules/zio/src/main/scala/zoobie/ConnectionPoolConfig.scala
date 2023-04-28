package zoobie

import java.time.Duration

/**
 * @param size
 *   number of connections to keep available
 * @param queueSize
 *   maximum number of requests waiting for a connection before a
 *   [[DatabaseError.Connection.Rejected]] is returned
 * @param maxConnectionLifetime
 *   maximum lifetime of a connection in the pool
 * @param validationTimeout
 *   the time-out to use when validating a connection after an error
 */
final case class ConnectionPoolConfig(
  name: String,
  size: Int,
  queueSize: Int,
  maxConnectionLifetime: Duration,
  validationTimeout: Duration,
)
