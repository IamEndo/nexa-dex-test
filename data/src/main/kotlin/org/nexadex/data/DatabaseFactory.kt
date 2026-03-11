package org.nexadex.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.nexadex.core.config.DatabaseConfig
import org.slf4j.LoggerFactory

object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(config: DatabaseConfig): HikariDataSource {
        logger.info("Connecting to database: {}", config.url)

        val dataSource = createDataSource(config)

        // Run Flyway migrations
        logger.info("Running database migrations...")
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
        flyway.repair() // Fix any checksum mismatches from modified migrations
        val result = flyway.migrate()
        logger.info("Applied {} migration(s)", result.migrationsExecuted)

        // Connect Exposed
        Database.connect(dataSource)
        logger.info("Database connected, pool size: {}", config.maxPoolSize)

        return dataSource
    }

    private fun createDataSource(config: DatabaseConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            minimumIdle = (config.maxPoolSize / 2).coerceAtLeast(2)
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            connectionTimeout = 10_000L
            validationTimeout = 5_000L
            idleTimeout = 300_000L
            maxLifetime = 600_000L
            leakDetectionThreshold = 30_000L
            connectionTestQuery = "SELECT 1"
            validate()
        }
        return HikariDataSource(hikariConfig)
    }
}
