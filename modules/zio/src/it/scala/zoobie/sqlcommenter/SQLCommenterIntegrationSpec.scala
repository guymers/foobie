package zoobie.sqlcommenter

import doobie.syntax.string.*
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import zio.Chunk
import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertCompletes
import zoobie.ConnectionPoolConfig
import zoobie.Transactor
import zoobie.postgres.PostgreSQLConnectionConfig
import zoobie.postgres.pool

import java.util.concurrent.TimeUnit

object SQLCommenterIntegrationSpec extends ZIOSpecDefault {

  override val spec = test("SQLCommenterIntegrationSpec") {
    val spanContext = new SpanContext {
      override val getTraceId = "3b120af54ca6f7efacddf3e538dd4988"
      override val getSpanId = "7cdf802020b41208"
      override val getTraceFlags = TraceFlags.getSampled
      override val getTraceState = TraceState.builder().put("key", "value").build()
      override val isRemote = false
    }
    val span = new Span {
      override def setAttribute[T](key: AttributeKey[T], value: T) = ???
      override def addEvent(name: String, attributes: Attributes) = ???
      override def addEvent(name: String, attributes: Attributes, timestamp: Long, unit: TimeUnit) = ???
      override def setStatus(statusCode: StatusCode, description: String) = ???
      override def recordException(exception: Throwable, additionalAttributes: Attributes) = ???
      override def updateName(name: String) = ???
      override def end(): Unit = ???
      override def end(timestamp: Long, unit: TimeUnit): Unit = ???
      override def isRecording = ???
      override def getSpanContext = spanContext
    }

    for {
      p <- pool(connectionConfig, config)
      interpreter = TraceInterpreter.create(Transactor.kleisliInterpreter, ZIO.succeed(Some(span)))
      transactor = Transactor(p.get, interpreter.ConnectionInterpreter, Transactor.strategies.transactional)
      _ <- transactor.run(fr"SELECT 1".query[Int].unique)
    } yield {
      assertCompletes
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
    size = 5,
    queueSize = 1_000,
    maxConnectionLifetime = 30.seconds,
    validationTimeout = 2.seconds,
  )
}
