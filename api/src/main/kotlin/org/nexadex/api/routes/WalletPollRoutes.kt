package org.nexadex.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nexadex.api.dto.ApiResponse
import org.nexadex.core.model.TradeDirection
import org.nexadex.service.SessionManager
import org.nexadex.service.SwapServiceV2
import org.nexadex.service.WsBrowserMessage
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger("org.nexadex.api.WalletPollRoutes")
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

// --- DTOs ---

@Serializable
data class TxReturnResponse(
    val status: String,
    val message: String? = null,
)

// --- Routes ---

fun Application.walletPollRoutes(sessionManager: SessionManager, swapServiceV2: SwapServiceV2) {
    routing {

        /**
         * GET /_lp?cookie={sessionId}&i={count}
         *
         * Long polling endpoint for Wally wallet.
         * Wally continuously polls this endpoint. Server holds the request
         * for up to 5 seconds waiting for a TDPP request to send.
         *
         * Returns:
         * - TDPP URI string if there's a pending request
         * - Empty string as keep-alive
         * - "Q" as quit signal (session expired/disconnected)
         */
        get("/_lp") {
            val cookie = call.request.queryParameters["cookie"]
            val count = call.request.queryParameters["i"]?.toIntOrNull()
            logger.info("/_lp: cookie={}, i={}", cookie?.take(6), count)

            if (cookie.isNullOrBlank()) {
                logger.info("/_lp: no cookie, sending Q")
                call.respondText("Q") // No cookie = quit
                return@get
            }

            val session = sessionManager.getSession(cookie)
            if (session == null) {
                logger.info("/_lp: session not found for {}, sending Q", cookie.take(6))
                call.respondText("Q") // Expired session = quit
                return@get
            }

            // Mark wallet as connected — only notify browsers on state change
            val wasConnected = session.walletConnected
            session.walletConnected = true
            session.lastWalletPoll = Instant.now()

            if (!wasConnected) {
                logger.info("/_lp: wallet connected for session={}", cookie.take(6))
                launch {
                    sessionManager.pushToBrowsers(session, WsBrowserMessage(
                        type = "wallet_connection",
                        data = """{"status":"connected"}""",
                    ))
                }
            }

            // First poll (i=0): respond immediately like NiftyArt
            if (count != null && count < 1) {
                logger.info("/_lp: initial connection, responding A")
                call.respondText("A")
                return@get
            }

            // Wait for a message (TDPP URI or other request)
            val message = sessionManager.receiveFromWalletChannel(session)

            if (message != null) {
                logger.info("Sending to wallet: {} (session={})", message.take(80), cookie.take(6))
                call.respondText(message)
            } else {
                // Keep-alive: empty response, wallet will poll again
                call.respondText("")
            }
        }

        /**
         * GET /tx?cookie={sessionId}&tx={signedTxHex}
         *
         * Wally returns a signed transaction here after processing a TDPP request.
         * Server validates the transaction and broadcasts it.
         */
        get("/tx") {
            val cookie = call.request.queryParameters["cookie"]
            val txHex = call.request.queryParameters["tx"]

            if (cookie.isNullOrBlank() || txHex.isNullOrBlank()) {
                call.respondText(
                    json.encodeToString(TxReturnResponse("error", "Missing cookie or tx parameter")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@get
            }

            val session = sessionManager.getSession(cookie)
            if (session == null) {
                call.respondText(
                    json.encodeToString(TxReturnResponse("error", "Session expired")),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                return@get
            }

            val pending = session.pendingTdpp
            if (pending == null) {
                call.respondText(
                    json.encodeToString(TxReturnResponse("error", "No pending transaction")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@get
            }

            logger.info("Received signed tx from Wally: session={}, action={}, txLen={}",
                cookie, pending.action, txHex.length)

            // Clear pending before processing
            session.pendingTdpp = null

            // Broadcast and record based on action type
            when (pending.action) {
                "swap" -> {
                    val dir = try {
                        TradeDirection.valueOf(pending.direction ?: "BUY")
                    } catch (e: Exception) {
                        TradeDirection.BUY
                    }

                    val result = swapServiceV2.broadcastAndRecordSwap(
                        signedTxHex = txHex,
                        poolId = pending.poolId,
                        direction = dir,
                        amountIn = pending.amountIn,
                        expectedAmountOut = pending.expectedAmountOut,
                        newNexReserve = pending.newNexReserve,
                        newTokenReserve = pending.newTokenReserve,
                    )

                    result
                        .onSuccess { swapResult ->
                            // Notify browsers with the real result
                            launch {
                                sessionManager.pushToBrowsers(session, WsBrowserMessage(
                                    type = "tx_signed",
                                    data = """{"action":"swap","txId":"${swapResult.txId}","poolId":${swapResult.poolId},"direction":"${swapResult.direction}","amountIn":${swapResult.amountIn},"amountOut":${swapResult.amountOut}}""",
                                ))
                            }

                            call.respondText(
                                json.encodeToString(TxReturnResponse("ok", "Swap broadcast: ${swapResult.txId}")),
                                ContentType.Application.Json,
                                HttpStatusCode.OK,
                            )
                        }
                        .onFailure { error ->
                            logger.warn("Swap broadcast failed: {}", error.message)

                            launch {
                                sessionManager.pushToBrowsers(session, WsBrowserMessage(
                                    type = "tx_error",
                                    data = """{"action":"swap","error":"${error.message}"}""",
                                ))
                            }

                            call.respondText(
                                json.encodeToString(TxReturnResponse("error", error.message)),
                                ContentType.Application.Json,
                                HttpStatusCode.BadRequest,
                            )
                        }
                }

                else -> {
                    // For LP and pool creation actions (future)
                    // Just notify browsers for now
                    launch {
                        sessionManager.pushToBrowsers(session, WsBrowserMessage(
                            type = "tx_signed",
                            data = """{"action":"${pending.action}","txHex":"$txHex","poolId":${pending.poolId}}""",
                        ))
                    }

                    call.respondText(
                        json.encodeToString(TxReturnResponse("ok", "Transaction received")),
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                }
            }
        }

        /**
         * POST /_walletRequest
         *
         * Browser sends a request to be forwarded to the connected wallet.
         * Used when wallet is already connected (no QR code needed).
         */
        post("/_walletRequest") {
            val cookie = call.request.queryParameters["cookie"]
                ?: call.request.cookies["dex_session"]
            val req = call.request.queryParameters["req"]
                ?: call.receiveText()

            if (cookie.isNullOrBlank() || req.isBlank()) {
                call.respondText(
                    json.encodeToString(ApiResponse.error<Unit>("INVALID_PARAM", "Missing cookie or request")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val session = sessionManager.getSession(cookie)
            if (session == null) {
                call.respondText(
                    json.encodeToString(ApiResponse.error<Unit>("SESSION_NOT_FOUND", "Session expired")),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                return@post
            }

            if (!session.walletConnected) {
                call.respondText(
                    json.encodeToString(ApiResponse.error<Unit>("WALLET_NOT_CONNECTED", "Wallet is not connected")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            // Push request to wallet channel (Wally will receive it on next long poll)
            sessionManager.pushToWallet(session, req)

            call.respondText(
                json.encodeToString(ApiResponse.success(mapOf("status" to "sent"))),
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }
}
