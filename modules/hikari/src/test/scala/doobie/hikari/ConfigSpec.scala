// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.hikari

import cats.effect.SyncIO
import com.zaxxer.hikari.HikariConfig
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object ConfigSpec extends ZIOSpecDefault {

  override val spec = suite("Config")(
    test("Default should be the same as unmodified HikariConfig") {
      val poolName = "poolName"

      val actual = Config.makeHikariConfig[SyncIO](Config("jdbcUrl", poolName = Some(poolName))).unsafeRunSync()
      val expected = {
        val c = new HikariConfig()
        c.setJdbcUrl("jdbcUrl") // mandatory argument
        c.setPoolName(poolName) // otherwise the pool name is generated
        c.validate()
        c
      }

      assertTrue(actual.getJdbcUrl == expected.getJdbcUrl) &&
      assertTrue(actual.getCatalog == expected.getCatalog) &&
      assertTrue(actual.getConnectionTimeout == expected.getConnectionTimeout) &&
      assertTrue(actual.getIdleTimeout == expected.getIdleTimeout) &&
      assertTrue(actual.getLeakDetectionThreshold == expected.getLeakDetectionThreshold) &&
      assertTrue(actual.getMaximumPoolSize == expected.getMaximumPoolSize) &&
      assertTrue(actual.getMaxLifetime == expected.getMaxLifetime) &&
      assertTrue(actual.getMinimumIdle == expected.getMinimumIdle) &&
      assertTrue(actual.getPassword == expected.getPassword) &&
      assertTrue(actual.getPoolName == expected.getPoolName) &&
      assertTrue(actual.getUsername == expected.getUsername) &&
      assertTrue(actual.getValidationTimeout == expected.getValidationTimeout) &&
      assertTrue(actual.isAllowPoolSuspension == expected.isAllowPoolSuspension) &&
      assertTrue(actual.isAutoCommit == expected.isAutoCommit) &&
      assertTrue(actual.getConnectionInitSql == expected.getConnectionInitSql) &&
      assertTrue(actual.getConnectionTestQuery == expected.getConnectionTestQuery) &&
      assertTrue(actual.getDataSourceClassName == expected.getDataSourceClassName) &&
      assertTrue(actual.getDataSourceJNDI == expected.getDataSourceJNDI) &&
      assertTrue(actual.getDriverClassName == expected.getDriverClassName) &&
      assertTrue(actual.getInitializationFailTimeout == expected.getInitializationFailTimeout) &&
      assertTrue(actual.isIsolateInternalQueries == expected.isIsolateInternalQueries) &&
      assertTrue(actual.isReadOnly == expected.isReadOnly) &&
      assertTrue(actual.isRegisterMbeans == expected.isRegisterMbeans) &&
      assertTrue(actual.getSchema == expected.getSchema) &&
      assertTrue(actual.getTransactionIsolation == expected.getTransactionIsolation) &&
      assertTrue(actual.getDataSource == expected.getDataSource) &&
      assertTrue(actual.getDataSourceProperties == expected.getDataSourceProperties) &&
      assertTrue(actual.getHealthCheckProperties == expected.getHealthCheckProperties) &&
      assertTrue(actual.getHealthCheckRegistry == expected.getHealthCheckRegistry) &&
      assertTrue(actual.getMetricRegistry == expected.getMetricRegistry) &&
      assertTrue(actual.getMetricsTrackerFactory == expected.getMetricsTrackerFactory) &&
      assertTrue(actual.getScheduledExecutor == expected.getScheduledExecutor) &&
      assertTrue(actual.getThreadFactory == expected.getThreadFactory)
    },
  )
}
