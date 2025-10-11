package doobie.bench

import cats.effect.IO
import doobie.free.connection.ConnectionIO
import doobie.syntax.connectionio.*
import doobie.util.transactor.Transactor
import org.openjdk.jmh.annotations.*

import java.sql.Connection
import java.sql.DriverManager

@State(Scope.Thread)
class PostgresConnectionState {

  var connection: Connection = _
  var xa: Transactor[IO] = _

  @Setup()
  def setup(): Unit = {
    connection = DriverManager.getConnection("jdbc:postgresql:world", "postgres", "password")
    xa = Transactor.fromConnection[IO](connection)
  }

  @TearDown()
  def tearDown(): Unit = {
    connection.close()
  }

  def transact[A](io: ConnectionIO[A]): A = {
    io.transact(xa).unsafeRunSync()(cats.effect.unsafe.implicits.global)
  }
}
