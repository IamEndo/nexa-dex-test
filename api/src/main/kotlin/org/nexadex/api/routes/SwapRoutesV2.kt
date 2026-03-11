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
import org.nexadex.core.model.TradeDirection
import org.nexadex.core.validation.InputValidator
import org.nexadex.service.SwapServiceV2
import org.slf4j.LoggerFactory

private val json = Json { encodeDefaults = true }
private val v2Logger = LoggerFactory.getLogger("org.nexadex.api.SwapRoutesV2")

fun Application.swapRoutesV2(
    swapServiceV2: SwapServiceV2,
    swapRateLimiter: RateLimiter,
) {
    routing {
        // Pool state (public, no auth)
        get("/api/v2/pools/{id}/state") {
            val poolId = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respondError("INVALID_PARAM", "Missing or invalid pool ID")

            swapServiceV2.getPoolState(poolId)
                .onSuccess { state ->
                    val resp = PoolStateResponse(
                        poolId = state.poolId,
                        tokenGroupIdHex = state.tokenGroupIdHex,
                        lpGroupIdHex = state.lpGroupIdHex,
                        contractAddress = state.contractAddress,
                        contractVersion = state.contractVersion,
                        status = state.status,
                        nexReserve = state.nexReserve,
                        tokenReserve = state.tokenReserve,
                        spotPrice = state.spotPrice,
                        poolUtxoTxId = state.poolUtxoTxId,
                        poolUtxoVout = state.poolUtxoVout,
                        initialLpSupply = state.initialLpSupply,
                        lpReserveBalance = state.lpReserveBalance,
                        lpInCirculation = state.lpInCirculation,
                        templateHashHex = "981d67323ac2dc3d73db0014b47640805832b2cd",
                        constraintHashHex = "",
                        lastUpdated = state.lastUpdated,
                    )
                    call.respondText(
                        json.encodeToString(ApiResponse.success(resp)),
                        ContentType.Application.Json,
                    )
                }
                .onFailure { error ->
                    call.respondError(error.type, error.message)
                }
        }

        // Quote (public, no auth)
        get("/api/v2/quote") {
            val rawPoolId = call.request.queryParameters["poolId"]?.toIntOrNull()
                ?: return@get call.respondError("INVALID_PARAM", "Missing or invalid poolId")
            val poolId = InputValidator.validatePoolId(rawPoolId).getOrElse { error ->
                return@get call.respondError(error.type, error.message)
            }
            val direction = call.request.queryParameters["direction"]?.uppercase()
                ?: return@get call.respondError("INVALID_PARAM", "Missing direction (BUY/SELL)")
            val rawAmountIn = call.request.queryParameters["amountIn"]?.toLongOrNull()
                ?: return@get call.respondError("INVALID_PARAM", "Missing or invalid amountIn")
            val amountIn = InputValidator.validateTradeAmount(rawAmountIn, direction).getOrElse { error ->
                return@get call.respondError(error.type, error.message)
            }
            val dir = try {
                TradeDirection.valueOf(direction)
            } catch (e: IllegalArgumentException) {
                return@get call.respondError("INVALID_PARAM", "direction must be BUY or SELL")
            }

            swapServiceV2.buildSwapParams(poolId, dir, amountIn)
                .onSuccess { params ->
                    val resp = SwapBuildResponse(
                        poolId = params.poolId,
                        direction = params.direction.name,
                        amountIn = params.amountIn,
                        amountOut = params.amountOut,
                        price = params.price,
                        priceImpactBps = params.priceImpactBps,
                        minimumReceived = params.minimumReceived,
                        poolOutputScriptHex = "",
                        poolOutputNexAmount = params.newPoolNex,
                        poolOutputTokenAmount = params.newPoolTokens,
                        userOutputAmount = params.amountOut,
                        estimatedFee = params.estimatedFee,
                        poolUtxoTxId = params.poolUtxoTxId,
                        poolUtxoVout = params.poolUtxoVout,
                    )
                    call.respondText(
                        json.encodeToString(ApiResponse.success(resp)),
                        ContentType.Application.Json,
                    )
                }
                .onFailure { error ->
                    call.respondError(error.type, error.message)
                }
        }

        // Execute swap (backend builds, signs, broadcasts using user's mnemonic)
        post("/api/v2/swap/execute") {
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
                json.decodeFromString<SwapExecuteRequest>(call.receiveText())
            } catch (e: Exception) {
                return@post call.respondError("INVALID_BODY", "Invalid JSON body")
            }

            if (req.mnemonic.isBlank()) {
                return@post call.respondError("INVALID_PARAM", "Mnemonic is required")
            }

            val dir = try {
                TradeDirection.valueOf(req.direction.uppercase())
            } catch (e: IllegalArgumentException) {
                return@post call.respondError("INVALID_PARAM", "direction must be BUY or SELL")
            }

            InputValidator.validatePoolId(req.poolId).getOrElse { error ->
                return@post call.respondError(error.type, error.message)
            }

            InputValidator.validateTradeAmount(req.amountIn, req.direction).getOrElse { error ->
                return@post call.respondError(error.type, error.message)
            }

            v2Logger.info("V2 swap execute: pool={}, dir={}, amount={}", req.poolId, dir, req.amountIn)

            swapServiceV2.executeSwap(req.poolId, dir, req.amountIn, req.mnemonic, req.maxSlippageBps)
                .onSuccess { result ->
                    val resp = SwapExecuteResponse(
                        txId = result.txId,
                        poolId = result.poolId,
                        direction = result.direction.name,
                        amountIn = result.amountIn,
                        amountOut = result.amountOut,
                        price = result.price,
                        status = "CONFIRMED",
                    )
                    call.respondText(
                        json.encodeToString(ApiResponse.success(resp)),
                        ContentType.Application.Json,
                    )
                }
                .onFailure { error ->
                    v2Logger.warn("V2 swap execute failed: {}", error.message)
                    call.respondError(error.type, error.message)
                }
        }

        // Broadcast relay (rate limited)
        post("/api/v2/swap/broadcast") {
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
                json.decodeFromString<SwapBroadcastRequest>(call.receiveText())
            } catch (e: Exception) {
                return@post call.respondError("INVALID_BODY", "Invalid JSON body")
            }

            if (req.signedTxHex.isBlank() || req.signedTxHex.length < 20) {
                return@post call.respondError("INVALID_PARAM", "signedTxHex is required")
            }

            swapServiceV2.broadcastTransaction(req.signedTxHex, req.poolId)
                .onSuccess { txId ->
                    val resp = SwapBroadcastResponse(txId = txId, poolId = req.poolId, status = "BROADCAST")
                    call.respondText(
                        json.encodeToString(ApiResponse.success(resp)),
                        ContentType.Application.Json,
                    )
                }
                .onFailure { error ->
                    call.respondError(error.type, error.message)
                }
        }
    }
}
