package zoobie.postgres

import cats.Monoid
import cats.syntax.foldable.*
import cats.syntax.semigroup.*
import cats.syntax.show.*
import doobie.syntax.string.*
import zio.Chunk
import zio.ZIO
import zio.durationInt
import zio.interop.catz.core.*
import zio.metrics.MetricState
import zio.stream.ZSink
import zio.stream.ZStream
import zio.test.TestAspect
import zio.test.TestRandom
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zoobie.ConnectionPoolConfig
import zoobie.DatabaseError
import zoobie.Transactor

object PostgreSQLIntegrationSpec extends ZIOSpecDefault {

  private val AvailableProcessors = Runtime.getRuntime.availableProcessors
  private val PoolSize = (AvailableProcessors * 2).max(4)

  private val NumOperations = 500_000 // takes ~42s

  private object Weightings {
    val success = 90
    val failure = 5
    val error = 5

    val total: Int = success + failure + error
  }

  override val spec = test("PostgreSQLIntegrationSpec") {

    val success = fr"SELECT 1".query[Int].unique.map(_ => Results.success)

    val failure = fr"SELECT a FROM table_does_not_exist".query[Int].option.map(_ => Results.failure)

    val error = for {
      _ <- fr"SET SESSION statement_timeout = '100'".update.run
      _ <- fr"SELECT pg_sleep(2)".update.run
    } yield Results.error

    def run(transactor: Transactor) = ZStream.iterate(1)(_ + 1)
      .takeWhile(_ <= NumOperations)
      .mapZIOParUnordered(PoolSize * 2) { _ =>
        for {
          conn <- zio.Random.nextIntBounded(Weightings.total).map { i =>
            if (i < Weightings.success) success
            else if (i < Weightings.success + Weightings.failure) failure
            else error
          }
          result <- transactor.run(conn).either
          results <- result match {
            case Left(e: DatabaseError.Utilization) =>
              if (e.msg.contains("""relation "table_does_not_exist" does not exist""")) {
                ZIO.succeed(Results.failure)
              } else if (e.msg.contains("canceling statement due to statement timeout")) {
                ZIO.succeed(Results.error)
              } else {
                ZIO.fail(e)
              }
            case Left(e) => ZIO.fail(e)
            case Right(r) => ZIO.succeed(r)
          }
        } yield results
      }
      .run(ZSink.foldLeftChunks(Results.monoid.empty) { case (results, chunk) => results |+| chunk.combineAll })

    for {
      _ <- TestRandom.setSeed(1234567890)
      p <- pool(connectionConfig, config)
      transactor = Transactor.fromPoolTransactional(p)
      results <- run(transactor)
      metrics = zio.internal.metrics.MetricRegistryExposed.snapshot
    } yield {
      val metricPairs = metrics.map { p =>
        val tags = p.metricKey.tags.toList.sortBy(_.key)
        val key = show"${p.metricKey.name}{${tags.map(l => show"${l.key}=${l.value}").mkString_(",")}}"
        val value = p.metricState match {
          case MetricState.Counter(count) => count
          case _: MetricState.Frequency => -1
          case MetricState.Gauge(value) => value
          case _: MetricState.Histogram => -1
          case _: MetricState.Summary => -1
        }
        (key, value)
      }
      // TODO number of results is not deterministic
      assertTrue(results == Results(
        success = 450301,
        failure = 24757,
        error = 24942,
      )) &&
      assertTrue(metricPairs == Set(
        ("zoobie_connections_waiting{pool=zoobie-postgres-it}", 0.0),
        ("zoobie_connections_in_use{pool=zoobie-postgres-it}", 0.0),
        ("zoobie_connections_created{pool=zoobie-postgres-it}", (PoolSize * 2).toDouble),
        ("zoobie_connections_invalidated{pool=zoobie-postgres-it}", PoolSize.toDouble),
      ))
    }
  }

  override val aspects = super.aspects ++ Chunk(
    TestAspect.timed,
    TestAspect.timeout(90.seconds),
    TestAspect.withLiveClock,
  )

  private lazy val connectionConfig = PostgreSQLConnectionConfig(
    host = "localhost",
    database = "world",
    username = "postgres",
    password = "password",
    applicationName = "doobie",
  )

  private lazy val config = ConnectionPoolConfig(
    name = "zoobie-postgres-it",
    size = PoolSize,
    queueSize = 1_000,
    maxConnectionLifetime = 30.seconds,
    validationTimeout = 2.seconds,
  )

  case class Results(
    success: Int,
    failure: Int,
    error: Int,
  )
  object Results {

    implicit val monoid: Monoid[Results] = new Monoid[Results] {
      override val empty = Results(success = 0, failure = 0, error = 0)
      override def combine(x: Results, y: Results) = Results(
        success = x.success + y.success,
        failure = x.failure + y.failure,
        error = x.error + y.error,
      )
    }

    val success = monoid.empty.copy(success = 1)
    val failure = monoid.empty.copy(failure = 1)
    val error = monoid.empty.copy(error = 1)
  }
}
