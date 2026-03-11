package org.nexadex.core.config

data class DexConfig(
    val server: ServerConfig,
    val database: DatabaseConfig,
    val rostrum: RostrumConfig,
    val security: SecurityConfig,
    val trading: TradingConfig,
    val indexer: IndexerConfig,
    val cors: CorsConfig,
)

data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 9090,
)

data class DatabaseConfig(
    val url: String = "jdbc:postgresql://localhost:5432/nexadex",
    val user: String = "nexadex",
    val password: String = "nexadex",
    val maxPoolSize: Int = 10,
)

data class RostrumConfig(
    val url: String = "wss://rostrum.nexa.org:20004",
    val useSsl: Boolean = true,
)

data class SecurityConfig(
    val rateLimitPerMinute: Int = 120,
    val swapRateLimitPerMinute: Int = 30,
)

data class TradingConfig(
    val maxSlippageBps: Int = 500,
    val maxPriceImpactBps: Int = 1500,
    val minTradeNexSats: Long = 546L,
    val tradeQueueDepth: Int = 50,
    val tradeTimeoutMs: Long = 30_000L,
)

data class IndexerConfig(
    val reconciliationIntervalMs: Long = 600_000L,
    val confirmationBlocks: Int = 1,
)

data class CorsConfig(
    val origins: String = "",
) {
    fun originList(): List<String> =
        origins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
