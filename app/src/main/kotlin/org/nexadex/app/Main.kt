package org.nexadex.app

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.nexadex.api.dexModule
import org.nexadex.core.config.*
import org.nexadex.core.math.AmmMath
import org.nexadex.data.DatabaseFactory
import org.nexadex.data.repository.*
import org.nexadex.indexer.ChainIndexer
import org.nexadex.service.*
import org.nexa.sdk.NexaSDK
import org.nexa.sdk.ensureSdkInitialized
import org.nexa.sdk.types.common.SdkResult
import org.nexa.sdk.types.config.ConnectionConfig
import org.nexa.sdk.types.config.ServerConfig as SdkServerConfig
import org.nexa.sdk.types.wallet.Network
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("NexaDEX")

fun main() {
    val startTime = System.currentTimeMillis()
    val config = loadConfig()

    logger.info("=== NexaDEX v2.0.0 ===")
    logger.info("Starting on {}:{}", config.server.host, config.server.port)

    // Initialize database
    val dataSource = DatabaseFactory.init(config.database)

    // Initialize repositories
    val tokenRepo = TokenRepository()
    val poolRepo = PoolRepository()
    val tradeRepo = TradeRepository()
    val ohlcvRepo = OhlcvRepository()
    val lpShareRepo = LpShareRepository()

    // Initialize services
    val eventBus = EventBus()
    val sessionManager = SessionManager()
    val serverFqdn = System.getenv("NEXADEX_SERVER_FQDN") ?: "localhost:9090"

    // Initialize SDK with NexID domain
    ensureSdkInitialized(nexIdDomain = serverFqdn)
    connectToRostrum(config.rostrum)

    // Fix LP fields for pools created before V5 migration
    fixMissingLpFields(poolRepo)
    val authService = AuthService(serverFqdn)
    val poolService = PoolService(poolRepo, tokenRepo, lpShareRepo)
    val swapServiceV2 = SwapServiceV2(poolRepo, tradeRepo, config.trading, eventBus, poolService)
    val liquidityService = LiquidityService(poolRepo, poolService)
    val analyticsService = AnalyticsService(poolRepo, tradeRepo, ohlcvRepo, eventBus)

    // Wire analytics to trade events
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    appScope.launch {
        eventBus.trades.collect { trade ->
            analyticsService.recordTradeInCandles(trade)
            val spotPrice = AmmMath.spotPrice(trade.nexReserveAfter, trade.tokenReserveAfter) ?: 0.0
            eventBus.emitPriceUpdate(
                PriceUpdate(
                    poolId = trade.poolId,
                    spotPrice = spotPrice,
                    nexReserve = trade.nexReserveAfter,
                    tokenReserve = trade.tokenReserveAfter,
                    lastTradeDirection = trade.direction.name,
                    lastTradeAmountIn = trade.amountIn,
                    lastTradeAmountOut = trade.amountOut,
                ),
            )
        }
    }

    // Start chain indexer
    val chainIndexer = ChainIndexer(poolService, poolRepo, tradeRepo, eventBus, config.indexer)
    chainIndexer.start(appScope)

    // Start session cleanup + wallet liveness monitor
    sessionManager.startCleanup(appScope)
    sessionManager.startWalletMonitor(appScope)

    // Start Ktor server
    logger.info("Starting HTTP server...")
    logger.info("")
    logger.info("Endpoints:")
    logger.info("  GET  /api/v1/health                     - Health check")
    logger.info("  GET  /api/v1/tokens                     - List tokens")
    logger.info("  POST /api/v1/tokens                     - Register token")
    logger.info("  GET  /api/v1/pools                      - List pools")
    logger.info("  POST /api/v1/pools                      - Create pool (V2)")
    logger.info("  GET  /api/v1/pools/{id}                 - Pool detail")
    logger.info("  GET  /api/v1/pools/{id}/trades          - Trade history")
    logger.info("  GET  /api/v1/pools/{id}/candles         - OHLCV candles")
    logger.info("  GET  /api/v1/pools/{id}/stats           - Pool statistics")
    logger.info("  GET  /api/v2/pools/{id}/state           - Pool state + UTXO")
    logger.info("  GET  /api/v2/quote                      - Swap quote + build params")
    logger.info("  POST /api/v2/swap/execute               - Execute swap (backend-assisted)")
    logger.info("  POST /api/v2/swap/broadcast             - Relay signed tx")
    logger.info("  GET  /api/v2/liquidity/quote             - LP quote (add/remove preview)")
    logger.info("  POST /api/v2/liquidity/add              - Add liquidity (permissionless)")
    logger.info("  POST /api/v2/liquidity/remove           - Remove liquidity (permissionless)")
    logger.info("  WS   /ws                                - Real-time feeds")
    logger.info("  --- Wally Wallet Integration ---")
    logger.info("  GET  /api/v1/auth/challenge              - NexID challenge")
    logger.info("  POST /api/v1/auth/verify                 - NexID verify")
    logger.info("  GET  /api/v1/auth/session                - Session status")
    logger.info("  POST /api/v1/auth/logout                 - Logout")
    logger.info("  POST /api/v2/swap/prepare                - TDPP swap prepare (Wally)")
    logger.info("  GET  /_lp                                - Wally long polling")
    logger.info("  GET  /tx                                 - Wally tx return + broadcast")
    logger.info("  WS   /ws/session                         - Browser session WS")

    val server = embeddedServer(Netty, port = config.server.port, host = config.server.host) {
        dexModule(config, poolService, swapServiceV2, liquidityService, analyticsService, eventBus, poolRepo, tokenRepo, tradeRepo, sessionManager, authService, startTime)
    }

    // Graceful shutdown
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down...")
        chainIndexer.stop()
        (dataSource as? java.io.Closeable)?.close()
        runBlocking { NexaSDK.connection.disconnect() }
        logger.info("Shutdown complete")
    })

    server.start(wait = true)
}

private fun loadConfig(): DexConfig {
    fun env(key: String, default: String = ""): String = System.getenv(key) ?: default
    fun envInt(key: String, default: Int): Int = System.getenv(key)?.toIntOrNull() ?: default
    fun envLong(key: String, default: Long): Long = System.getenv(key)?.toLongOrNull() ?: default
    fun envBool(key: String, default: Boolean): Boolean =
        System.getenv(key)?.lowercase()?.let { it == "true" } ?: default

    return DexConfig(
        server = ServerConfig(
            host = env("NEXADEX_HOST", "0.0.0.0"),
            port = envInt("NEXADEX_PORT", 9090),
        ),
        database = DatabaseConfig(
            url = env("NEXADEX_DB_URL", "jdbc:postgresql://localhost:5432/nexadex"),
            user = env("NEXADEX_DB_USER", "nexadex"),
            password = env("NEXADEX_DB_PASSWORD", "nexadex"),
            maxPoolSize = envInt("NEXADEX_DB_POOL_SIZE", 10),
        ),
        rostrum = RostrumConfig(
            url = env("NEXADEX_ROSTRUM_URL", "wss://electrum.nexa.org:20004"),
            useSsl = envBool("NEXADEX_ROSTRUM_SSL", true),
        ),
        security = SecurityConfig(
            rateLimitPerMinute = envInt("NEXADEX_RATE_LIMIT", 120),
            swapRateLimitPerMinute = envInt("NEXADEX_SWAP_RATE_LIMIT", 30),
        ),
        trading = TradingConfig(
            maxSlippageBps = envInt("NEXADEX_MAX_SLIPPAGE_BPS", 500),
            maxPriceImpactBps = envInt("NEXADEX_MAX_PRICE_IMPACT_BPS", 1500),
            minTradeNexSats = envLong("NEXADEX_MIN_TRADE_SATS", 546L),
            tradeQueueDepth = envInt("NEXADEX_TRADE_QUEUE_DEPTH", 50),
            tradeTimeoutMs = envLong("NEXADEX_TRADE_TIMEOUT_MS", 30_000L),
        ),
        indexer = IndexerConfig(
            reconciliationIntervalMs = envLong("NEXADEX_RECONCILE_INTERVAL_MS", 600_000L),
            confirmationBlocks = envInt("NEXADEX_CONFIRMATION_BLOCKS", 1),
        ),
        cors = CorsConfig(
            origins = env("NEXADEX_CORS_ORIGINS", ""),
        ),
    )
}

/**
 * Fix pools that were created before V5 migration added lp_group_id_hex / initial_lp_supply.
 * Extracts the correct values from the serialized contract blob.
 */
private fun fixMissingLpFields(poolRepo: PoolRepository) {
    val pools = poolRepo.findAll()
    for (pool in pools) {
        if (pool.lpGroupIdHex.isNotEmpty() && pool.initialLpSupply > 10_000L) continue
        if (pool.contractBlob.isEmpty()) continue

        try {
            val instance = org.nexadex.service.ContractInstanceSerializer.deserialize(pool.contractBlob)
            val lpGroupId = instance.args["lpGroupId"] ?: continue
            val lpSupplyStr = instance.args["initialLpSupply"] ?: continue
            val lpSupply = lpSupplyStr.toLongOrNull() ?: continue

            if (lpGroupId.isNotEmpty() && lpSupply > 0) {
                logger.info("Fixing LP fields for pool {}: lpGroupId={}, initialLpSupply={}", pool.poolId, lpGroupId, lpSupply)
                poolRepo.updateLpFields(pool.poolId, lpGroupId, lpSupply)
            }
        } catch (e: Exception) {
            logger.warn("Could not fix LP fields for pool {}: {}", pool.poolId, e.message)
        }
    }
}

private fun connectToRostrum(config: RostrumConfig) {
    logger.info("Connecting to Rostrum: {}", config.url)

    // Parse URL to extract host and port
    val url = config.url
    val cleanUrl = url.removePrefix("wss://").removePrefix("ws://")
    val parts = cleanUrl.split(":")
    val host = parts[0]
    val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 20004 else 20004

    runBlocking {
        val connConfig = ConnectionConfig(
            network = Network.MAINNET,
            servers = listOf(
                SdkServerConfig(
                    host = host,
                    port = port,
                    useSsl = config.useSsl,
                ),
            ),
        )
        when (val result = NexaSDK.connection.connect(connConfig)) {
            is SdkResult.Success -> logger.info("Connected to Rostrum")
            is SdkResult.Failure -> {
                logger.error("Failed to connect to Rostrum: {}", result.error)
                throw RuntimeException("Cannot connect to Rostrum: ${result.error}")
            }
        }
    }
}
