package org.nexadex.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nexadex.api.dto.*
import org.nexadex.api.middleware.RateLimiter
import org.nexadex.data.repository.PoolRepository
import org.nexadex.service.LiquidityService
import org.slf4j.LoggerFactory

private val json = Json { encodeDefaults = true }
private val lpLogger = LoggerFactory.getLogger("org.nexadex.api.LiquidityRoutes")

fun Application.liquidityRoutes(
    liquidityService: LiquidityService,
    poolRepo: PoolRepository,
    swapRateLimiter: RateLimiter,
) {
    routing {
        // LP quote/preview (public, no auth)
        get("/api/v2/liquidity/quote") {
            val poolId = call.request.queryParameters["poolId"]?.toIntOrNull()
                ?: return@get call.respondError("INVALID_PARAM", "Missing or invalid poolId")
            val action = call.request.queryParameters["action"]?.uppercase()
                ?: return@get call.respondError("INVALID_PARAM", "Missing action (ADD/REMOVE)")

            val pool = poolRepo.findById(poolId)
                ?: return@get call.respondError("POOL_NOT_FOUND", "Pool $poolId not found")

            val lpState = liquidityService.getPoolLpState(pool)
                .getOrElse { error ->
                    return@get call.respondError(error.type, error.message)
                }

            val resp = when (action) {
                "ADD" -> {
                    val nexSats = call.request.queryParameters["nexSats"]?.toLongOrNull()
                        ?: return@get call.respondError("INVALID_PARAM", "Missing or invalid nexSats")
                    val tokenAmount = call.request.queryParameters["tokenAmount"]?.toLongOrNull()
                        ?: return@get call.respondError("INVALID_PARAM", "Missing or invalid tokenAmount")

                    val lpTokens = liquidityService.computeAddLiquidityQuote(
                        nexSats, tokenAmount,
                        lpState.nexReserve.satoshis, lpState.tokenReserve,
                        lpState.lpInCirculation,
                    )

                    LiquidityQuoteResponse(
                        poolId = poolId,
                        action = "ADD",
                        nexAmount = nexSats,
                        tokenAmount = tokenAmount,
                        lpTokenAmount = lpTokens,
                        lpInCirculation = lpState.lpInCirculation,
                        poolNexReserve = lpState.nexReserve.satoshis,
                        poolTokenReserve = lpState.tokenReserve,
                    )
                }
                "REMOVE" -> {
                    val lpTokenAmount = call.request.queryParameters["lpTokenAmount"]?.toLongOrNull()
                        ?: return@get call.respondError("INVALID_PARAM", "Missing or invalid lpTokenAmount")

                    val (nexOut, tokensOut) = liquidityService.computeRemoveLiquidityQuote(
                        lpTokenAmount,
                        lpState.nexReserve.satoshis, lpState.tokenReserve,
                        lpState.lpInCirculation,
                    )

                    LiquidityQuoteResponse(
                        poolId = poolId,
                        action = "REMOVE",
                        nexAmount = nexOut,
                        tokenAmount = tokensOut,
                        lpTokenAmount = lpTokenAmount,
                        lpInCirculation = lpState.lpInCirculation,
                        poolNexReserve = lpState.nexReserve.satoshis,
                        poolTokenReserve = lpState.tokenReserve,
                    )
                }
                else -> return@get call.respondError("INVALID_PARAM", "action must be ADD or REMOVE")
            }

            call.respondText(
                json.encodeToString(ApiResponse.success(resp)),
                ContentType.Application.Json,
            )
        }

        // Add liquidity (permissionless)
        post("/api/v2/liquidity/add") {
            val clientIp = call.request.local.remoteAddress
            if (!swapRateLimiter.tryConsume(clientIp)) {
                call.respondText(
                    json.encodeToString(
                        ApiResponse.error<Unit>("RATE_LIMITED", "Too many requests", retryable = true),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.TooManyRequests,
                )
                return@post
            }

            val req = try {
                json.decodeFromString<AddLiquidityRequest>(call.receiveText())
            } catch (e: Exception) {
                return@post call.respondError("INVALID_BODY", "Invalid JSON body")
            }

            if (req.cookie.isNotBlank()) {
                // Wallet-based: use POST /api/v2/liquidity/prepare instead
                return@post call.respondError("USE_PREPARE", "Wallet-based liquidity: use POST /api/v2/liquidity/prepare with cookie")
            }
            if (req.mnemonic.isBlank()) {
                return@post call.respondError("INVALID_PARAM", "Mnemonic is required")
            }
            if (req.nexSats <= 0 || req.tokenAmount <= 0) {
                return@post call.respondError("INVALID_PARAM", "nexSats and tokenAmount must be positive")
            }

            lpLogger.info("Add liquidity: pool={}, nex={}, tokens={}", req.poolId, req.nexSats, req.tokenAmount)

            liquidityService.addLiquidity(req.poolId, req.nexSats, req.tokenAmount, req.mnemonic)
                .onSuccess { result ->
                    val resp = LiquidityResponse(
                        txId = result.txId,
                        poolId = result.poolId,
                        action = result.action,
                        nexAmount = result.nexAmount,
                        tokenAmount = result.tokenAmount,
                        lpTokenAmount = result.lpTokenAmount,
                        status = "CONFIRMED",
                    )
                    call.respondText(
                        json.encodeToString(ApiResponse.success(resp)),
                        ContentType.Application.Json,
                    )
                }
                .onFailure { error ->
                    lpLogger.warn("Add liquidity failed: {}", error.message)
                    call.respondError(error.type, error.message)
                }
        }

        // Remove liquidity (permissionless)
        post("/api/v2/liquidity/remove") {
            val clientIp = call.request.local.remoteAddress
            if (!swapRateLimiter.tryConsume(clientIp)) {
                call.respondText(
                    json.encodeToString(
                        ApiResponse.error<Unit>("RATE_LIMITED", "Too many requests", retryable = true),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.TooManyRequests,
                )
                return@post
            }

            val req = try {
                json.decodeFromString<RemoveLiquidityRequest>(call.receiveText())
            } catch (e: Exception) {
                return@post call.respondError("INVALID_BODY", "Invalid JSON body")
            }

            if (req.cookie.isNotBlank()) {
                // Wallet-based: use POST /api/v2/liquidity/prepare instead
                return@post call.respondError("USE_PREPARE", "Wallet-based liquidity: use POST /api/v2/liquidity/prepare with cookie")
            }
            if (req.mnemonic.isBlank()) {
                return@post call.respondError("INVALID_PARAM", "Mnemonic is required")
            }
            if (req.lpTokenAmount <= 0) {
                return@post call.respondError("INVALID_PARAM", "lpTokenAmount must be positive")
            }

            lpLogger.info("Remove liquidity: pool={}, lpTokens={}", req.poolId, req.lpTokenAmount)

            liquidityService.removeLiquidity(req.poolId, req.lpTokenAmount, req.mnemonic)
                .onSuccess { result ->
                    val resp = LiquidityResponse(
                        txId = result.txId,
                        poolId = result.poolId,
                        action = result.action,
                        nexAmount = result.nexAmount,
                        tokenAmount = result.tokenAmount,
                        lpTokenAmount = result.lpTokenAmount,
                        status = "CONFIRMED",
                    )
                    call.respondText(
                        json.encodeToString(ApiResponse.success(resp)),
                        ContentType.Application.Json,
                    )
                }
                .onFailure { error ->
                    lpLogger.warn("Remove liquidity failed: {}", error.message)
                    call.respondError(error.type, error.message)
                }
        }
    }
}
