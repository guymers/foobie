// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.bench

import cats.syntax.apply.*
import doobie.syntax.string.*
import doobie.util.Read
import org.openjdk.jmh.annotations.*

import java.sql.Connection
import java.util.concurrent.TimeUnit

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class select {
  import select.*

  // Baseline hand-written JDBC code
  private def jdbcBench(co: Connection, n: Int): Int = {
    try {
      co.setAutoCommit(false)
      val ps = co.prepareStatement("select a.name, b.name, co.name from country a, country b, country co limit ?")
      try {
        ps.setInt(1, n)
        val rs = ps.executeQuery
        try {
          val accum = List.newBuilder[(String, String, String)]
          while (rs.next) {
            val a = rs.getString(1); rs.wasNull
            val b = rs.getString(2); rs.wasNull
            val c = rs.getString(3); rs.wasNull
            accum += ((a, b, c))
          }
          accum.result().length
        } finally rs.close
      } finally ps.close
    } finally {
      co.commit()
    }
  }

  // Reading via .stream, which adds a fair amount of overhead
  private def doobieBenchP(n: Int) =
    sql"select a.name, b.name, c.name from country a, country b, country c limit $n"
      .query[(String, String, String)]
      .stream
      .compile.toList
      .map(_.length)

  // Reading via .list, which uses a lower-level collector
  private def doobieBench(n: Int) =
    sql"select a.name, b.name, c.name from country a, country b, country c limit $n"
      .query[(String, String, String)]
      .to[List]
      .map(_.length)

  @Benchmark
  @OperationsPerInvocation(1000)
  def list_accum_1000_jdbc(state: PostgresConnectionState): Int = jdbcBench(state.connection, 1000)

  @Benchmark
  @OperationsPerInvocation(1000)
  def list_accum_1000(state: PostgresConnectionState): Int = state.transact(doobieBench(1000))

  @Benchmark
  @OperationsPerInvocation(1000)
  def stream_accum_1000(state: PostgresConnectionState): Int = state.transact(doobieBenchP(1000))

}
object select {

  implicit val read3Strings: Read[(String, String, String)] = (
    Read[String],
    Read[String],
    Read[String],
  ).tupled
}
