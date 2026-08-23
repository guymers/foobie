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
 * @param alwaysInvalidateOnFailure
 *   if true, always invalidate a connection when an error occurs while it is
 *   checked out of the pool, instead of only invalidating when it is too old or
 *   fails validation
 */
final case class ConnectionPoolConfig(
  name: String,
  size: Int,
  queueSize: Int,
  maxConnectionLifetime: Duration,
  validationTimeout: Duration,
  alwaysInvalidateOnFailure: Boolean,
)
