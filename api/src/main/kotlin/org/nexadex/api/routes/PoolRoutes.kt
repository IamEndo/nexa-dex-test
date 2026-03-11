package org.nexadex.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nexadex.api.dto.*
import org.nexadex.core.math.AmmMath
import org.nexadex.core.validation.InputValidator
import org.nexadex.core.model.CandleInterval
import org.nexadex.core.model.Token
import org.nexadex.data.repository.TokenRepository
import org.nexadex.data.repository.TradeRepository
import org.nexadex.service.AnalyticsService
import org.nexadex.service.PoolService

private val json = Json { encodeDefaults = true }

fun Application.poolRoutes(
    poolService: PoolService,
    analyticsService: AnalyticsService,
    tradeRepo: TradeRepository,
    tokenRepo: TokenRepository,
) {
    routing {
        // List all pools
        get("/api/v1/pools") {
            val pools = poolService.getAllPools().map { pool ->
                val token = tokenRepo.findByGroupId(pool.tokenGroupIdHex)
                val spotPrice = AmmMath.spotPrice(pool.nexReserve, pool.tokenReserve) ?: 0.0
                PoolResponse(
                    poolId = pool.poolId,
                    tokenGroupIdHex = pool.tokenGroupIdHex,
                    tokenTicker = token?.ticker,
                    tokenDecimals = token?.decimals ?: 0,
                    contractAddress = pool.contractAddress,
                    contractVersion = pool.contractVersion,
                    status = pool.status.name,
                    nexReserve = pool.nexReserve,
                    tokenReserve = pool.tokenReserve,
                    spotPrice = spotPrice,
                    tvlNexSats = AmmMath.computeTvl(pool.nexReserve),
                    deployTxId = pool.deployTxId,
                )
            }
            call.respondText(
                json.encodeToString(ApiResponse.success(pools)),
                ContentType.Application.Json,
            )
        }

        // Create a new V2 pool (public, permissionless)
        post("/api/v1/pools") {
            val req = try {
                json.decodeFromString<CreatePoolRequest>(call.receiveText())
            } catch (e: Exception) {
                return@post call.respondError("INVALID_BODY", "Invalid or malformed JSON body")
            }

            InputValidator.validateGroupIdHex(req.tokenGroupIdHex).onFailure { error ->
                return@post call.respondError(error.type, error.message)
            }
            if (req.lpGroupIdHex.isNotEmpty()) {
                InputValidator.validateGroupIdHex(req.lpGroupIdHex).onFailure { error ->
                    return@post call.respondError(error.type, error.message)
                }
            }
            if (req.initialNexSats < AmmMath.DUST_THRESHOLD) {
                return@post call.respondError("INVALID_PARAM", "initialNexSats must be >= ${AmmMath.DUST_THRESHOLD}")
            }
            if (req.initialTokenAmount <= 0) {
                return@post call.respondError("INVALID_PARAM", "initialTokenAmount must be > 0")
            }
            if (req.initialLpSupply < 2000) {
                return@post call.respondError("INVALID_PARAM", "initialLpSupply must be >= 2000")
            }
            if (req.mnemonic.isBlank()) {
                return@post call.respondError("INVALID_PARAM", "Mnemonic is required")
            }

            val result = poolService.createPool(
                req.tokenGroupIdHex, req.lpGroupIdHex, req.initialLpSupply,
                req.initialNexSats, req.initialTokenAmount, req.mnemonic,
            )
            result.onSuccess { pool ->
                val spotPrice = AmmMath.spotPrice(pool.nexReserve, pool.tokenReserve) ?: 0.0
                val token = tokenRepo.findByGroupId(pool.tokenGroupIdHex)
                val resp = PoolResponse(
                    poolId = pool.poolId,
                    tokenGroupIdHex = pool.tokenGroupIdHex,
                    tokenTicker = token?.ticker,
                    tokenDecimals = token?.decimals ?: 0,
                    contractAddress = pool.contractAddress,
                    contractVersion = pool.contractVersion,
                    status = pool.status.name,
                    nexReserve = pool.nexReserve,
                    tokenReserve = pool.tokenReserve,
                    spotPrice = spotPrice,
                    tvlNexSats = AmmMath.computeTvl(pool.nexReserve),
                    deployTxId = pool.deployTxId,
                )
                call.respondText(
                    json.encodeToString(ApiResponse.success(resp)),
                    ContentType.Application.Json,
                    HttpStatusCode.Created,
                )
            }.onFailure { error ->
                call.respondError(error.type, error.message)
            }
        }

        // Register an already-deployed pool (no on-chain action)
        post("/api/internal/register-pool") {
            val req = try {
                json.decodeFromString<RegisterPoolRequest>(call.receiveText())
            } catch (e: Exception) {
                return@post call.respondError("INVALID_BODY", "Invalid JSON body")
            }

            val pool = org.nexadex.core.model.Pool(
                tokenGroupIdHex = req.tokenGroupIdHex,
                lpGroupIdHex = req.lpGroupIdHex,
                initialLpSupply = req.initialLpSupply,
                contractAddress = req.contractAddress,
                deployTxId = req.deployTxId,
                poolUtxoTxId = req.deployTxId,
                poolUtxoVout = 0,
                nexReserve = req.nexReserve,
                tokenReserve = req.tokenReserve,
                status = org.nexadex.core.model.PoolStatus.ACTIVE,
                contractVersion = "v3",
            )
            val inserted = poolService.registerPool(pool)
            val token = tokenRepo.findByGroupId(inserted.tokenGroupIdHex)
            val spotPrice = AmmMath.spotPrice(inserted.nexReserve, inserted.tokenReserve) ?: 0.0
            val resp = PoolResponse(
                poolId = inserted.poolId,
                tokenGroupIdHex = inserted.tokenGroupIdHex,
                tokenTicker = token?.ticker,
                tokenDecimals = token?.decimals ?: 0,
                contractAddress = inserted.contractAddress,
                contractVersion = inserted.contractVersion,
                status = inserted.status.name,
                nexReserve = inserted.nexReserve,
                tokenReserve = inserted.tokenReserve,
                spotPrice = spotPrice,
                tvlNexSats = AmmMath.computeTvl(inserted.nexReserve),
                deployTxId = inserted.deployTxId,
            )
            call.respondText(
                json.encodeToString(ApiResponse.success(resp)),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        }

        // Pool detail
        get("/api/v1/pools/{poolId}") {
            val poolId = call.parameters["poolId"]?.toIntOrNull()
                ?: return@get call.respondError("INVALID_PARAM", "Invalid pool ID")

            val result = poolService.getPool(poolId)
            result.onSuccess { pool ->
                val token = tokenRepo.findByGroupId(pool.tokenGroupIdHex)
                val spotPrice = AmmMath.spotPrice(pool.nexReserve, pool.tokenReserve) ?: 0.0
                val resp = PoolResponse(
                    poolId = pool.poolId,
                    tokenGroupIdHex = pool.tokenGroupIdHex,
                    tokenTicker = token?.ticker,
                    tokenDecimals = token?.decimals ?: 0,
                    contractAddress = pool.contractAddress,
                    contractVersion = pool.contractVersion,
                    status = pool.status.name,
                    nexReserve = pool.nexReserve,
                    tokenReserve = pool.tokenReserve,
                    spotPrice = spotPrice,
                    tvlNexSats = AmmMath.computeTvl(pool.nexReserve),
                    deployTxId = pool.deployTxId,
                )
                call.respondText(
                    json.encodeToString(ApiResponse.success(resp)),
                    ContentType.Application.Json,
                )
            }.onFailure { error ->
                call.respondError(error.type, error.message)
            }
        }

        // Pool trade history
        get("/api/v1/pools/{poolId}/trades") {
            val poolId = call.parameters["poolId"]?.toIntOrNull()
                ?: return@get call.respondError("INVALID_PARAM", "Invalid pool ID")
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0

            val trades = tradeRepo.findByPool(poolId, limit.coerceIn(1, 200), offset).map { trade ->
                TradeResponse(
                    tradeId = trade.tradeId,
                    poolId = trade.poolId,
                    direction = trade.direction.name,
                    amountIn = trade.amountIn,
                    amountOut = trade.amountOut,
                    price = trade.price,
                    txId = trade.txId,
                    status = trade.status.name,
                    createdAt = trade.createdAt,
                )
            }
            call.respondText(
                json.encodeToString(ApiResponse.success(trades)),
                ContentType.Application.Json,
            )
        }

        // Pool stats
        get("/api/v1/pools/{poolId}/stats") {
            val poolId = call.parameters["poolId"]?.toIntOrNull()
                ?: return@get call.respondError("INVALID_PARAM", "Invalid pool ID")

            val pool = poolService.getPool(poolId).getOrNull()
                ?: return@get call.respondError("POOL_NOT_FOUND", "Pool $poolId not found")

            val token = tokenRepo.findByGroupId(pool.tokenGroupIdHex)
            val stats = analyticsService.getPoolStats(poolId, token)
                ?: return@get call.respondError("POOL_NOT_FOUND", "Pool $poolId stats unavailable")

            val resp = StatsResponse(
                poolId = stats.poolId,
                nexReserve = stats.nexReserve,
                tokenReserve = stats.tokenReserve,
                spotPrice = stats.spotPrice,
                tvlNexSats = stats.tvlNexSats,
                volume24hNex = stats.volume24hNex,
                volume24hToken = stats.volume24hToken,
                tradeCount24h = stats.tradeCount24h,
                priceChange24hPct = stats.priceChange24hPct,
                apyEstimatePct = stats.apyEstimatePct,
            )
            call.respondText(
                json.encodeToString(ApiResponse.success(resp)),
                ContentType.Application.Json,
            )
        }

        // OHLCV candles
        get("/api/v1/pools/{poolId}/candles") {
            val poolId = call.parameters["poolId"]?.toIntOrNull()
                ?: return@get call.respondError("INVALID_PARAM", "Invalid pool ID")

            val intervalStr = call.request.queryParameters["interval"] ?: "1h"
            val interval = CandleInterval.fromLabel(intervalStr)
                ?: return@get call.respondError("INVALID_PARAM", "Invalid interval: $intervalStr")

            val now = System.currentTimeMillis()
            val from = call.request.queryParameters["from"]?.toLongOrNull() ?: (now - 86_400_000L)
            val to = call.request.queryParameters["to"]?.toLongOrNull() ?: now
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500

            val candles = analyticsService.getCandles(poolId, interval, from, to, limit.coerceIn(1, 1000))
                .map { c ->
                    CandleResponse(
                        openTime = c.openTime,
                        open = c.open,
                        high = c.high,
                        low = c.low,
                        close = c.close,
                        volumeNex = c.volumeNex,
                        volumeToken = c.volumeToken,
                        tradeCount = c.tradeCount,
                    )
                }
            call.respondText(
                json.encodeToString(ApiResponse.success(candles)),
                ContentType.Application.Json,
            )
        }
    }
}

internal suspend fun ApplicationCall.respondError(
    type: String,
    message: String,
    status: HttpStatusCode = HttpStatusCode.BadRequest,
) {
    respondText(
        json.encodeToString(ApiResponse.error<Unit>(type, message)),
        ContentType.Application.Json,
        status,
    )
}
