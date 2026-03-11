package org.nexadex.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nexadex.api.dto.ApiResponse
import org.nexadex.core.model.TradeDirection
import org.nexadex.core.validation.InputValidator
import org.nexadex.service.PendingTdpp
import org.nexadex.service.SessionManager
import org.nexadex.service.SwapServiceV2
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.nexadex.api.TdppRoutes")
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable
data class SwapPrepareRequest(
    val poolId: Int,
    val direction: String,
    val amountIn: Long,
    val cookie: String,
    val maxSlippageBps: Int = 100,
)

@Serializable
data class SwapPrepareResponse(
    val status: String,
    val poolId: Int,
    val direction: String,
    val amountIn: Long,
    val amountOut: Long,
    val price: Double,
    val priceImpactBps: Int,
)

fun Application.tdppRoutes(
    swapServiceV2: SwapServiceV2,
    sessionManager: SessionManager,
    serverFqdn: String,
) {
    routing {

        /**
         * POST /api/v2/swap/prepare
         *
         * Builds a partial swap transaction (contract side only) and pushes
         * a TDPP URI to the connected Wally wallet. Wally adds user funding
         * inputs, signs, and returns the complete tx via GET /tx.
         *
         * Flow:
         * 1. Validate session + wallet connection
         * 2. Build partial tx via SDK (pool input + expected outputs)
         * 3. Build TDPP URI and push to Wally via long-poll channel
         * 4. Return OK — frontend waits for wally:tx_signed via WS
         */
        post("/api/v2/swap/prepare") {
            val req = try {
                json.decodeFromString<SwapPrepareRequest>(call.receiveText())
            } catch (e: Exception) {
                return@post call.respondError("INVALID_BODY", "Invalid JSON body")
            }

            // Validate session
            val session = sessionManager.getSession(req.cookie)
            if (session == null) {
                return@post call.respondText(
                    json.encodeToString(ApiResponse.error<Unit>("SESSION_NOT_FOUND", "Session expired or invalid")),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
            }

            if (!session.walletConnected) {
                return@post call.respondText(
                    json.encodeToString(ApiResponse.error<Unit>("WALLET_NOT_CONNECTED", "Wally wallet not connected")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }

            if (session.pendingTdpp != null) {
                return@post call.respondText(
                    json.encodeToString(ApiResponse.error<Unit>("TDPP_PENDING", "A transaction is already pending confirmation")),
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
            }

            // Validate inputs
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

            // For SELL swaps, require NexID auth (need user address for token UTXOs)
            val userAddress = if (dir == TradeDirection.SELL) {
                session.nexAddress
                    ?: return@post call.respondError(
                        "AUTH_REQUIRED",
                        "NexID authentication required for SELL swaps (need address for token lookup)",
                    )
            } else null

            // Build partial transaction via SDK
            val tdppResult = swapServiceV2.prepareSwapTdpp(
                req.poolId, dir, req.amountIn, req.maxSlippageBps, userAddress,
            ).getOrElse { error ->
                return@post call.respondError(error.type, error.message)
            }

            // Store pending TDPP
            session.pendingTdpp = PendingTdpp(
                poolId = tdppResult.poolId,
                action = "swap",
                direction = dir.name,
                amountIn = tdppResult.amountIn,
                expectedAmountOut = tdppResult.amountOut,
                newNexReserve = tdppResult.newPoolNex,
                newTokenReserve = tdppResult.newPoolTokens,
                partialTxHex = tdppResult.partialTxHex,
            )

            // Build TDPP URI and push to Wally
            // Flags: NOPOST(2) | NOSHUFFLE(4) | PARTIAL(8) = 14 for both BUY and SELL
            // SELL no longer uses FUND_GROUPS(16) because user token inputs are pre-included
            val flags = 14
            // inamt = total satoshis of inputs already in partial tx
            // For BUY: just the pool UTXO's NEX reserve
            // For SELL: pool UTXO's NEX + user token input dust amounts (from SDK)
            val inamt = if (tdppResult.totalInputSatoshis > 0) {
                tdppResult.totalInputSatoshis
            } else {
                // Fallback for backward compat: reverse-compute from reserves
                when (dir) {
                    TradeDirection.BUY -> tdppResult.newPoolNex - tdppResult.amountIn
                    TradeDirection.SELL -> tdppResult.newPoolNex + tdppResult.amountOut
                }
            }
            val tdppUri = buildTdppUri(serverFqdn, req.cookie, tdppResult.partialTxHex, flags, inamt)
            sessionManager.pushToWallet(session, tdppUri)

            logger.info(
                "Swap prepared: pool={}, dir={}, in={}, out={}, txLen={}, session={}",
                req.poolId, dir, tdppResult.amountIn, tdppResult.amountOut,
                tdppResult.partialTxHex.length, req.cookie.take(6) + "...",
            )

            call.respondText(
                json.encodeToString(
                    ApiResponse.success(
                        SwapPrepareResponse(
                            status = "PENDING_WALLET",
                            poolId = tdppResult.poolId,
                            direction = dir.name,
                            amountIn = tdppResult.amountIn,
                            amountOut = tdppResult.amountOut,
                            price = tdppResult.price,
                            priceImpactBps = tdppResult.priceImpactBps,
                        ),
                    ),
                ),
                ContentType.Application.Json,
            )
        }
    }
}

/**
 * Build a TDPP URI for Wally to process.
 *
 * Format: tdpp://<server>/tx?chain=nexa&inamt=<sats>&flags=<flags>&tx=<hex>&cookie=<id>
 *
 * Parameters (matching Wally's tricklePaySession.kt handleTxAutopay):
 *   chain  — blockchain selector (required)
 *   inamt  — total satoshis of existing inputs (required unless NOFUND flag set)
 *   flags  — TDPP flag bitmask (NOFUND=1, NOPOST=2, NOSHUFFLE=4, PARTIAL=8, FUND_GROUPS=16)
 *   tx     — partial transaction hex
 *   cookie — session ID for reply routing
 *
 * Wally receives this via long polling, adds user funding inputs, signs,
 * and returns the signed tx via GET /tx?cookie=<session>&tx=<signedHex>.
 */
private fun buildTdppUri(
    serverFqdn: String,
    cookie: String,
    partialTxHex: String,
    flags: Int,
    inamt: Long,
): String {
    return "tdpp://$serverFqdn/tx?chain=nexa&inamt=$inamt&flags=$flags&rproto=https&tx=$partialTxHex&cookie=$cookie"
}
