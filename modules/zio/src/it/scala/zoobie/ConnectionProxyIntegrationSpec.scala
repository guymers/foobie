package zoobie

import zio.Chunk
import zio.ZIO
import zio.durationInt
import zio.test.TestAspect
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zoobie.mysql.MySQLConnectionConfig
import zoobie.postgres.PostgreSQLConnectionConfig

import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

object ConnectionProxyIntegrationSpec extends ZIOSpecDefault {

  override val spec = suite("ConnectionProxyIntegrationSpec")(
    tests("PostgreSQL", postgres.url, postgres.props, transactionIdle = c => Some(postgresTransactionIdle(c))),
    tests("MySQL", mysql.url, mysql.props, transactionIdle = _ => None),
  )

  private def tests(name: String, url: String, props: Properties, transactionIdle: Connection => Option[Boolean]) = {

    /**
     * A pool of one connection and a way to get the underlying connection it
     * most recently created.
     */
    val setup = for {
      physicalRef <- ZIO.succeed(new AtomicReference[Connection]())
      create = ZIO.attemptBlocking {
        val c = DriverManager.getConnection(url, props)
        physicalRef.set(c)
        c
      }.mapError(DatabaseError.Connection(_))
      pool <- ConnectionPool.create(create, config(name))
      _ <- use(pool)(execute(_, "CREATE TEMPORARY TABLE proxy_it (id INT)"))
    } yield (pool, () => physicalRef.get)

    suite(name)(
      test("uncommitted work is rolled back when the connection is returned") {
        for {
          s <- setup
          (pool, _) = s
          _ <- use(pool) { c =>
            c.setAutoCommit(false)
            execute(c, "INSERT INTO proxy_it VALUES (1)")
          }
          rows <- use(pool)(count)
        } yield {
          assertTrue(rows == 0)
        }
      },
      test("work done after a commit through a statement created before it is rolled back") {
        for {
          s <- setup
          (pool, _) = s
          _ <- use(pool) { c =>
            c.setAutoCommit(false)
            val statement = c.prepareStatement("INSERT INTO proxy_it VALUES (1)")
            try {
              val _ = statement.executeUpdate()
              c.commit()
              val _ = statement.executeUpdate()
            } finally {
              statement.close()
            }
          }
          rows <- use(pool)(count)
        } yield {
          assertTrue(rows == 1)
        }
      },
      test("auto-commit is restored when the connection is returned") {
        for {
          s <- setup
          (pool, physical) = s
          _ <- use(pool) { c =>
            c.setAutoCommit(false)
            execute(c, "INSERT INTO proxy_it VALUES (1)")
            c.commit()
          }
          afterTransactional <- ZIO.attemptBlocking(physical().getAutoCommit)
          // relies on auto-commit being on without setting it
          _ <- use(pool)(execute(_, "INSERT INTO proxy_it VALUES (2)"))
          rows <- use(pool)(count)
        } yield {
          assertTrue(afterTransactional) &&
          assertTrue(rows == 2)
        }
      },
      test("changed state is restored and no transaction is left open") {
        for {
          s <- setup
          (pool, physical) = s
          initial <- use(pool)(state)
          changed <- use(pool) { c =>
            c.setAutoCommit(false)
            c.setReadOnly(true)
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE)
            state(c)
          }
          idle <- ZIO.attemptBlocking(transactionIdle(physical()))
          networkTimeout <- ZIO.attemptBlocking(physical().getNetworkTimeout)
          restored <- use(pool)(state)
        } yield {
          assertTrue(changed != initial) &&
          assertTrue(restored == initial) &&
          assertTrue(idle.forall(identity)) &&
          // only set while the connection is being reset
          assertTrue(networkTimeout == 0)
        }
      },
    )
  }

  private def use[A](pool: ConnectionPool)(f: Connection => A): ZIO[Any, Throwable, A] = ZIO.scoped[Any] {
    pool.get.flatMap(c => ZIO.attemptBlocking(f(c)))
  }

  private def execute(c: Connection, sql: String): Unit = {
    val statement = c.createStatement()
    try {
      val _ = statement.execute(sql)
    } finally {
      statement.close()
    }
  }

  private def count(c: Connection): Int = {
    val statement = c.createStatement()
    try {
      val rs = statement.executeQuery("SELECT COUNT(*) FROM proxy_it")
      val _ = rs.next()
      rs.getInt(1)
    } finally {
      statement.close()
    }
  }

  private def state(c: Connection) = (c.isReadOnly, c.getTransactionIsolation)

  private def postgresTransactionIdle(c: Connection) = {
    c.unwrap(classOf[org.postgresql.core.BaseConnection]).getTransactionState == org.postgresql.core.TransactionState.IDLE
  }

  override val aspects = super.aspects ++ Chunk(
    TestAspect.timed,
    TestAspect.timeout(60.seconds),
    TestAspect.withLiveClock,
  )

  private lazy val postgres = PostgreSQLConnectionConfig(
    host = "localhost",
    database = "world",
    username = "postgres",
    password = "password",
    applicationName = "doobie",
  )

  private lazy val mysql = MySQLConnectionConfig(
    host = "localhost",
    database = "world",
    username = "root",
    password = "password",
  )

  private def config(name: String) = ConnectionPoolConfig(
    name = s"zoobie-proxy-it-$name",
    size = 1,
    queueSize = 10,
    maxConnectionLifetime = 10.minutes,
    validationTimeout = 2.seconds,
    alwaysInvalidateOnFailure = false,
  )
}
