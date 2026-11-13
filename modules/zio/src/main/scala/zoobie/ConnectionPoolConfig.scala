package zoobie

import java.time.Duration

/**
 * @param size
 *   maximum number of connections, each is created when a request needs one. A
 *   connection being created counts towards this even if the request it was for
 *   gave up waiting, until creation succeeds or fails, so creation must not
 *   hang. A connection being closed does not count
 * @param queueSize
 *   maximum number of requests waiting for a connection before a
 *   [[DatabaseError.Connection.Rejected]] is returned, must not be negative.
 *   Zero rejects requests immediately when every connection is in use
 * @param maxConnectionLifetime
 *   maximum lifetime of a connection in the pool, at most 365 days. Each
 *   connection gets a lifetime of 90-100% of this so they do not all expire at
 *   once. Idle connections whose lifetime has ended are closed every 30
 *   seconds, one in use is closed when it is returned
 * @param validationTimeout
 *   timeout for validating a connection after an error and for resetting its
 *   state when it is returned to the pool. A connection that does not respond
 *   in time is replaced. Zero, or a negative value, means validation has no
 *   timeout and the reset uses the connection's own network timeout
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
) {

  private[zoobie] def errors: List[String] = List(
    Option.when(size <= 0)(s"pool size must be positive, was ${size.toString}"),
    Option.when(queueSize < 0)(s"queue size must not be negative, was ${queueSize.toString}"),
    Option.when(maxConnectionLifetime.isNegative || maxConnectionLifetime.isZero)(
      s"max connection lifetime must be positive, was ${maxConnectionLifetime.toString}",
    ),
    Option.when(maxConnectionLifetime.compareTo(Duration.ofDays(365)) > 0)(
      s"max connection lifetime must not be more than 365 days, was ${maxConnectionLifetime.toString}",
    ),
  ).flatten
}
