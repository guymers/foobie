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
 *   time-out for validating a connection after an error and for resetting its
 *   state when it is returned to the pool. A connection that does not respond
 *   in time is replaced. Zero, or a negative value, means validation has no
 *   time-out and the reset uses the connection's own network time-out
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
