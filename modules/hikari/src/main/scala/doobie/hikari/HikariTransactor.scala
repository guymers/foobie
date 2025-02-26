// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie
package hikari

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.effect.syntax.resource.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.metrics.MetricsTrackerFactory
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor

import java.util.Properties
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import javax.sql.DataSource
import scala.concurrent.ExecutionContext

object HikariTransactor {

  /** Construct a `HikariTransactor` from an existing `HikariDatasource`. */
  def apply[M[_]: Async](
    hikariDataSource: HikariDataSource,
    connectEC: ExecutionContext,
  ): HikariTransactor[M] =
    Transactor.fromDataSource[M](hikariDataSource, connectEC)

  /** Resource yielding an unconfigured `HikariTransactor`. */
  def initial[M[_]: Async](
    connectEC: ExecutionContext,
  ): Resource[M, HikariTransactor[M]] = {
    Resource.fromAutoCloseable(Sync[M].delay(new HikariDataSource))
      .map(Transactor.fromDataSource[M](_, connectEC))
  }

  /**
   * Resource yielding a new `HikariTransactor` configured with the given
   * Config. Unless you have a good reason, consider using `fromConfig` which
   * creates the `connectEC` for you.
   */
  def fromConfigCustomEc[M[_]: Async](
    config: Config,
    connectEC: ExecutionContext,
    dataSource: Option[DataSource] = None,
    dataSourceProperties: Option[Properties] = None,
    healthCheckProperties: Option[Properties] = None,
    healthCheckRegistry: Option[Object] = None,
    metricRegistry: Option[Object] = None,
    metricsTrackerFactory: Option[MetricsTrackerFactory] = None,
    scheduledExecutor: Option[ScheduledExecutorService] = None,
    threadFactory: Option[ThreadFactory] = None,
  ): Resource[M, HikariTransactor[M]] = {
    Resource
      .liftK(
        Config.makeHikariConfig(
          config,
          dataSource,
          dataSourceProperties,
          healthCheckProperties,
          healthCheckRegistry,
          metricRegistry,
          metricsTrackerFactory,
          scheduledExecutor,
          threadFactory,
        ),
      )
      .flatMap(fromHikariConfig(_, connectEC))
  }

  /**
   * Resource yielding a new `HikariTransactor` configured with the given
   * Config. The `connectEC` is created automatically, with the same size as the
   * Hikari pool.
   */
  def fromConfig[M[_]: Async](
    config: Config,
    dataSource: Option[DataSource] = None,
    dataSourceProperties: Option[Properties] = None,
    healthCheckProperties: Option[Properties] = None,
    healthCheckRegistry: Option[Object] = None,
    metricRegistry: Option[Object] = None,
    metricsTrackerFactory: Option[MetricsTrackerFactory] = None,
    scheduledExecutor: Option[ScheduledExecutorService] = None,
    threadFactory: Option[ThreadFactory] = None,
  ): Resource[M, HikariTransactor[M]] = {
    Resource
      .liftK(
        Config.makeHikariConfig(
          config,
          dataSource,
          dataSourceProperties,
          healthCheckProperties,
          healthCheckRegistry,
          metricRegistry,
          metricsTrackerFactory,
          scheduledExecutor,
          threadFactory,
        ),
      )
      .flatMap(fromHikariConfig(_))
  }

  /**
   * Resource yielding a new `HikariTransactor` configured with the given
   * HikariConfig. Unless you have a good reason, consider using the overload
   * without explicit `connectEC`, it will be created automatically for you.
   */
  def fromHikariConfig[M[_]: Async](
    hikariConfig: HikariConfig,
    connectEC: ExecutionContext,
  ): Resource[M, HikariTransactor[M]] = Resource
    .fromAutoCloseable(Sync[M].delay(new HikariDataSource(hikariConfig)))
    .map(Transactor.fromDataSource[M](_, connectEC))

  /**
   * Resource yielding a new `HikariTransactor` configured with the given
   * HikariConfig. The `connectEC` is created automatically, with the same size
   * as the Hikari pool.
   */
  def fromHikariConfig[M[_]: Async](hikariConfig: HikariConfig): Resource[M, HikariTransactor[M]] =
    for {
      // to populate unset fields with default values, like `maximumPoolSize`
      _ <- Sync[M].delay(hikariConfig.validate()).toResource
      // Note that the number of JDBC connections is usually limited by the underlying JDBC pool.
      // You may therefore want to limit your connection pool to the same size as the underlying JDBC pool
      // as any additional threads are guaranteed to be blocked.
      // https://tpolecat.github.io/doobie/docs/14-Managing-Connections.html#about-threading
      connectEC <- ExecutionContexts.fixedThreadPool(hikariConfig.getMaximumPoolSize)
      result <- fromHikariConfig(hikariConfig, connectEC)
    } yield result

  /**
   * Resource yielding a new `HikariTransactor` configured with the given info.
   * Consider using `fromConfig` for better configurability.
   */
  def newHikariTransactor[M[_]: Async](
    driverClassName: String,
    url: String,
    user: String,
    pass: String,
    connectEC: ExecutionContext,
  ): Resource[M, HikariTransactor[M]] =
    for {
      _ <- Resource.eval(Async[M].delay(Class.forName(driverClassName)))
      t <- initial[M](connectEC)
      _ <- Resource.eval {
        t.configure { ds =>
          Async[M].delay {
            ds.setJdbcUrl(url)
            ds.setUsername(user)
            ds.setPassword(pass)
          }
        }
      }
    } yield t

}
