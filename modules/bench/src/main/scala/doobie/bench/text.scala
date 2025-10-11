// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.bench

import cats.syntax.apply.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import doobie.FPS
import doobie.HC
import doobie.HPS
import doobie.free.connection.ConnectionIO
import doobie.postgres.syntax.fragment.*
import doobie.syntax.string.*
import doobie.util.Write
import fs2.Stream
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class text {
  import text.*

  def people(n: Int): List[Person] =
    List.fill(n)(Person("Bob", 42))

  def naive(n: Int): ConnectionIO[Int] =
    HC.prepareStatement("insert into bench_person (name, age) values (?, ?)")(
      people(n).foldRight(HPS.executeBatch)((p, k) => HPS.set(p) *> HPS.addBatch *> k),
    ).map(_.combineAll)

  def optimized(n: Int): ConnectionIO[Int] =
    HC.prepareStatement("insert into bench_person (name, age) values (?, ?)")(
      FPS.raw { ps =>
        people(n).foreach { p =>
          ps.setString(1, p.name)
          ps.setInt(2, p.age)
          ps.addBatch()
        }
        ps.executeBatch.sum
      },
    )

  def copyin_stream(n: Int): ConnectionIO[Long] =
    sql"COPY bench_person (name, age) FROM STDIN".copyIn(Stream.emits[ConnectionIO, Person](people(n)), 10000)

  def copyin_foldable(n: Int): ConnectionIO[Long] =
    sql"COPY bench_person (name, age) FROM STDIN".copyIn(people(n))

  @Benchmark
  @OperationsPerInvocation(10000)
  def batch(state: state): Int = state.transact(naive(10000))

  @Benchmark
  @OperationsPerInvocation(10000)
  def batch_optimized(state: state): Int = state.transact(optimized(10000))

  @Benchmark
  @OperationsPerInvocation(10000)
  def copy_stream(state: state): Long = state.transact(copyin_stream(10000))

  @Benchmark
  @OperationsPerInvocation(10000)
  def copy_foldable(state: state): Long = state.transact(copyin_foldable(10000))
}
object text {

  final case class Person(name: String, age: Int)
  object Person {
    implicit val write: Write[Person] = Write.derived
  }

  private val ddl: ConnectionIO[Unit] =
    sql"drop table if exists bench_person".update.run *>
      sql"create table bench_person (name varchar not null, age integer not null)".update.run.void

  @State(Scope.Thread)
  class state extends PostgresConnectionState {

    @Setup()
    override def setup(): Unit = {
      super.setup()
      transact(ddl)
    }

    @TearDown()
    override def tearDown(): Unit = super.tearDown()
  }
}
