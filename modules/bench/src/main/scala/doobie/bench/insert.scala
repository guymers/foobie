package doobie.bench

import cats.syntax.apply.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import doobie.free.connection.ConnectionIO
import doobie.syntax.string.*
import doobie.util.Write
import doobie.util.update.Update
import org.openjdk.jmh.annotations.*

import java.time.LocalDate
import java.util.concurrent.TimeUnit

@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class insert {
  import insert.*

  private def batch(n: Int) = {
    val widgets = Widget.generate(n)
    insertBatch.updateMany(widgets)
  }

  private def values(n: Int) = {
    val widgets = Widget.generate(n)
    insertValues(widgets).run
  }

  private def array(n: Int) = {
    val widgets = Widget.generate(n)
    insertArrayValues(widgets).run
  }

  @Benchmark
  @OperationsPerInvocation(512)
  def batch_512(state: state): Int = state.transact(batch(512))

  @Benchmark
  @OperationsPerInvocation(1024)
  def batch_1024(state: state): Int = state.transact(batch(1024))

  @Benchmark
  @OperationsPerInvocation(2048)
  def batch_2048(state: state): Int = state.transact(batch(2048))

  @Benchmark
  @OperationsPerInvocation(512)
  def values_512(state: state): Int = state.transact(values(512))

  @Benchmark
  @OperationsPerInvocation(1024)
  def values_1024(state: state): Int = state.transact(values(1024))

  @Benchmark
  @OperationsPerInvocation(2048)
  def values_2048(state: state): Int = state.transact(values(2048))

  @Benchmark
  @OperationsPerInvocation(512)
  def array_512(state: state): Int = state.transact(array(512))

  @Benchmark
  @OperationsPerInvocation(1024)
  def array_1024(state: state): Int = state.transact(array(1024))

  @Benchmark
  @OperationsPerInvocation(2048)
  def array_2048(state: state): Int = state.transact(array(2048))
}
object insert {
  import doobie.postgres.instances.array.*

  final case class Widget(name: String, extensions: Int, produced: LocalDate)
  object Widget {
    implicit val write: Write[Widget] = Write.derived

    private val now = LocalDate.now()

    def generate(n: Int) = Vector.fill(n)(Widget("widget", n, now.plusDays(n.toLong)))
  }

  private val insertBatch = {
    val sql = """
      INSERT INTO bench_widget
      (name, extensions, produced)
      VALUES
      (?, ?, ?)
    """
    Update[Widget](sql)
  }

  private def insertValues(widgets: Vector[Widget]) = {
    val sql = fr"""
      INSERT INTO bench_widget
      (name, extensions, produced)
      VALUES
      ${widgets.map(w => fr"(${w.name}, ${w.extensions}, ${w.produced})").intercalate(fr",")}
    """
    sql.update
  }

  private def insertArrayValues(widgets: Vector[Widget]) = {
    val n = widgets.length
    val names = Array.ofDim[String](n)
    val extensions = Array.ofDim[Int](n)
    val produced = Array.ofDim[LocalDate](n)
    var i = 0
    while (i < n) {
      val w = widgets(i)
      names(i) = w.name
      extensions(i) = w.extensions
      produced(i) = w.produced
      i = i + 1
    }

    val sql = fr"""
      INSERT INTO bench_widget
      (name, extensions, produced)
      SELECT * FROM unnest(
        $names::text[],
        $extensions::int4[],
        $produced::date[]
      )
    """
    sql.update
  }

  private val ddl: ConnectionIO[Unit] =
    sql"drop table if exists bench_widget".update.run *>
      sql"""create table bench_widget (
        name text not null,
        extensions integer not null,
        produced date not null
      )""".update.run.void

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
