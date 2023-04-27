package zoobie

import doobie.util.invariant.InvariantViolation

import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import scala.util.control.NonFatal

@SuppressWarnings(Array("org.wartremover.warts.Null"))
sealed abstract class DatabaseError(
  val msg: String,
  cause: Option[Throwable],
) extends RuntimeException(msg, cause.orNull)
object DatabaseError {

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def apply(t: Throwable): DatabaseError = t match {
    case e: DatabaseError => e
    case t if Connection.partial.isDefinedAt(t) => Connection.partial(t)
    case e: InvariantViolation => Utilization.Invariant(e)
    case e: SQLException => Utilization.SQL(e)
    case t if NonFatal(t) => Unhandled(t)
    case t => throw t
  }

  sealed trait Connection extends DatabaseError
  object Connection {
    final case class Transient(
      cause: SQLTransientConnectionException,
    ) extends DatabaseError(cause.getMessage, Some(cause)) with Connection

    final case class Rejected(
      queueSize: Int,
    ) extends DatabaseError(s"Maximum queue size of ${queueSize.toString} reached", None) with Connection

    @SuppressWarnings(Array("org.wartremover.warts.Throw"))
    def apply(t: Throwable): DatabaseError.Connection = t match {
      case t if partial.isDefinedAt(t) => partial(t)
      case t if NonFatal(t) => Unhandled(t)
      case t => throw t
    }

    val partial: PartialFunction[Throwable, DatabaseError.Connection] = {
      case e: DatabaseError.Connection => e
      case e: SQLTransientConnectionException => Transient(e)
    }
  }

  sealed trait Utilization extends DatabaseError
  object Utilization {
    final case class Invariant(e: InvariantViolation) extends DatabaseError(e.getMessage, Some(e)) with Utilization
    final case class SQL(e: SQLException) extends DatabaseError(e.getMessage, Some(e)) with Utilization
  }

  final case class Unhandled(
    cause: Throwable,
  ) extends DatabaseError("Unhandled database error", Some(cause)) with Connection with Utilization
}
