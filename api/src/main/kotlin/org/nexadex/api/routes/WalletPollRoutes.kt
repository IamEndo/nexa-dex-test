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
import org.nexadex.service.TricklePayAssetList
import org.nexadex.service.WalletAssetUtxo
import org.nexadex.service.WsBrowserMessage
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Parse a grouped output prevout hex to extract group ID and token amount.
 *
 * The prevout may be:
 * a) Just the script: starts with 0x20 (push 32 bytes of groupId)
 * b) Full serialized output: [type:1][amount:8LE][scriptLen:varint][script]
 *
 * The script itself starts with: [0x20][groupId:32bytes][amtLen:1byte][amtLE:amtLen bytes]...
 *
 * Fallback: search for the 0x20 push marker followed by 32 bytes + valid amount length.
 *
 * Returns Pair(groupIdHex, tokenAmount) or null if not a grouped output.
 */
private fun parseGroupedPrevout(prevoutHex: String): Pair<String, Long>? {
    try {
        if (prevoutHex.length < 72) return null

        // Try multiple offsets: the script might start at different positions
        // depending on whether prevout is full output or just script
        val offsets = mutableListOf<Int>()

        // Offset 0: prevout IS the script
        offsets.add(0)

        // Search for 0x20 (push 32 bytes) anywhere in the hex — that's our group ID push
        var searchFrom = 0
        while (searchFrom < prevoutHex.length - 68) {
            val idx = prevoutHex.indexOf("20", searchFrom)
            if (idx < 0 || idx >= prevoutHex.length - 68) break
            if (idx % 2 == 0) offsets.add(idx) // only even positions (byte boundaries)
            searchFrom = idx + 2
        }

        for (hexOffset in offsets) {
            val result = tryParseGroupAtOffset(prevoutHex, hexOffset)
            if (result != null) return result
        }

        return null
    } catch (e: Exception) {
        return null
    }
}

private fun tryParseGroupAtOffset(hex: String, hexOffset: Int): Pair<String, Long>? {
    try {
        if (hexOffset + 68 > hex.length) return null
        val pushByte = hex.substring(hexOffset, hexOffset + 2).toInt(16)
        if (pushByte != 0x20) return null // must be push-32-bytes

        // Group ID: 32 bytes
        val groupIdHex = hex.substring(hexOffset + 2, hexOffset + 66)

        // Validate: group ID should end with "0000" (Nexa group IDs end with 00 subgroup)
        if (!groupIdHex.endsWith("0000")) return null

        // Group amount: next byte is push length
        if (hexOffset + 68 > hex.length) return null
        val amtLen = hex.substring(hexOffset + 66, hexOffset + 68).toInt(16)
        if (amtLen != 2 && amtLen != 4 && amtLen != 8) return null
        if (hexOffset + 68 + amtLen * 2 > hex.length) return null

        // Read amount (little-endian)
        val amtHex = hex.substring(hexOffset + 68, hexOffset + 68 + amtLen * 2)
        var amount = 0L
        for (i in 0 until amtLen) {
            val byteVal = amtHex.substring(i * 2, i * 2 + 2).toLong(16)
            amount = amount or (byteVal shl (i * 8))
        }
        if (amount <= 0) return null

        return Pair(groupIdHex, amount)
    } catch (e: Exception) {
        return null
    }
}

/**
 * Extract P2ST address from raw hex bytes. Looks for the pattern:
 * 00 51 14 <20-byte-hash> (OP_0 OP_1 PUSH20 constraintArgsHash)
 */
private fun extractAddressFromHex(hex: String): String? {
    // Find "005114" in the hex string
    val marker = "005114"
    val idx = hex.indexOf(marker)
    if (idx < 0 || idx + marker.length + 40 > hex.length) return null
    val hashHex = hex.substring(idx + marker.length, idx + marker.length + 40)
    val hashBytes = ByteArray(20) { i ->
        ((Character.digit(hashHex[i * 2], 16) shl 4) + Character.digit(hashHex[i * 2 + 1], 16)).toByte()
    }
    return try {
        when (val addr = org.nexa.sdk.types.primitives.Address.fromComponents(
            org.nexa.sdk.types.wallet.Network.MAINNET,
            org.nexa.sdk.types.wallet.AddressType.PAY_TO_SCRIPT_TEMPLATE,
            hashBytes,
        )) {
            is org.nexa.sdk.types.common.SdkResult.Success -> addr.value.toString()
            is org.nexa.sdk.types.common.SdkResult.Failure -> null
        }
    } catch (e: Exception) { null }
}

private val logger = LoggerFactory.getLogger("org.nexadex.api.WalletPollRoutes")
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

// --- DTOs ---

@Serializable
data class TxReturnResponse(
    val status: String,
    val message: String? = null,
)

// --- Routes ---

fun Application.walletPollRoutes(sessionManager: SessionManager, swapServiceV2: SwapServiceV2, serverFqdn: String) {
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
                // Request wallet address via TDPP share mechanism.
                // Wally will POST its address to /_share?cookie=SESSION.
                if (session.nexAddress == null) {
                    val shareUri = "tdpp://$serverFqdn/share?info=address&rproto=https&cookie=$cookie"
                    logger.info("/_lp: requesting address via share: {}", shareUri.take(60))
                    sessionManager.pushToWallet(session, shareUri)
                }

                // Request asset list (NiftyArt pattern): Wally scans ALL HD addresses
                // for grouped UTXOs and POSTs results to /assets?cookie=SESSION.
                // af=f1f1 is SatoshiScript(TEMPLATE, TMPL_DATA, TMPL_DATA) — matches any grouped output.
                if (!session.assetsLoaded) {
                    val assetUri = "tdpp://$serverFqdn/assets?chain=nexa&af=f1f1&rproto=https&cookie=$cookie"
                    logger.info("/_lp: requesting wallet assets: {}", assetUri.take(70))
                    sessionManager.pushToWallet(session, assetUri)
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
         * POST /_share?cookie={sessionId}
         *
         * Wally posts shared information here after processing a TDPP share request.
         * For info=address, body contains the wallet's Nexa address string.
         * This is how we get the user's address without NexID auth.
         */
        post("/_share") {
            val cookie = call.request.queryParameters["cookie"]
            val body = call.receiveText().trim()

            if (cookie.isNullOrBlank()) {
                call.respondText("error: no cookie", status = HttpStatusCode.BadRequest)
                return@post
            }

            val session = sessionManager.getSession(cookie)
            if (session == null) {
                call.respondText("error: session not found", status = HttpStatusCode.Unauthorized)
                return@post
            }

            if (body.startsWith("nexa:")) {
                session.nexAddress = body
                session.knownTokenAddresses.add(body)
                logger.info("/_share: got wallet address for session={}: {}", cookie.take(6), body.take(20) + "...")

                // Notify browsers that we now have the address
                launch {
                    sessionManager.pushToBrowsers(session, WsBrowserMessage(
                        type = "wallet_connection",
                        data = """{"status":"authenticated","address":"$body"}""",
                    ))
                }
            } else {
                logger.info("/_share: received non-address data for session={}: {}", cookie.take(6), body.take(30))
            }

            call.respondText("ok")
        }

        /**
         * POST /assets?cookie={sessionId}
         *
         * Wally posts its grouped UTXOs here after processing a TDPP asset query.
         * Body is JSON matching TricklePayAssetList: {"assets": [{"outpointHash":"...", "amt":123, "prevout":"..."}]}
         * This is the NiftyArt-proven pattern for discovering token UTXOs across
         * all HD wallet addresses. Used for SELL swaps.
         */
        post("/assets") {
            val cookie = call.request.queryParameters["cookie"]
            val body = call.receiveText().trim()

            if (cookie.isNullOrBlank()) {
                call.respondText("error: no cookie", status = HttpStatusCode.BadRequest)
                return@post
            }

            val session = sessionManager.getSession(cookie)
            if (session == null) {
                call.respondText("error: session not found", status = HttpStatusCode.Unauthorized)
                return@post
            }

            try {
                val assetList = json.decodeFromString<TricklePayAssetList>(body)
                logger.info("/assets: received {} assets for session={}", assetList.assets.size, cookie.take(6))

                // Clear old assets and store new ones
                session.walletAssets.clear()
                for (asset in assetList.assets) {
                    // Parse prevout hex to extract group ID and token amount.
                    // Grouped script format: pushData(groupId:32bytes) pushData(groupAmountLE) [locking_script]
                    // pushData for 32 bytes: 0x20 [32 bytes]
                    // pushData for 2-8 bytes: [length_byte] [amount_bytes]
                    val parsed = parseGroupedPrevout(asset.prevout)

                    session.walletAssets.add(
                        WalletAssetUtxo(
                            outpointHash = asset.outpointHash,
                            amount = asset.amt,
                            prevoutHex = asset.prevout,
                            groupIdHex = parsed?.first,
                            tokenAmount = parsed?.second ?: 0,
                        )
                    )
                    // Extract P2ST address from the prevout script
                    val addr = extractAddressFromHex(asset.prevout)
                    if (addr != null) {
                        session.knownTokenAddresses.add(addr)
                    }
                    logger.info("/assets: UTXO outpoint={}, nexAmt={}, groupId={}, tokenAmt={}, addr={}, prevout[0..40]={}",
                        asset.outpointHash.take(16), asset.amt,
                        parsed?.first?.take(16) ?: "PARSE_FAIL", parsed?.second ?: 0,
                        addr?.take(20) ?: "N/A",
                        asset.prevout.take(80))
                }
                session.assetsLoaded = true

                // Notify browsers that assets have been loaded
                launch {
                    sessionManager.pushToBrowsers(session, WsBrowserMessage(
                        type = "assets_loaded",
                        data = """{"count":${assetList.assets.size}}""",
                    ))
                }
            } catch (e: Exception) {
                logger.warn("/assets: failed to parse asset list for session={}: {}", cookie.take(6), e.message)
                // Even on error, mark as loaded so we don't retry endlessly
                session.assetsLoaded = true
            }

            call.respondText("ok")
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

            logger.info("/tx callback: cookie={}, txPresent={}, txLen={}, queryKeys={}",
                cookie?.take(8) ?: "NULL", txHex != null, txHex?.length ?: 0,
                call.request.queryParameters.names().joinToString(","))

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
                            // Capture token delivery address from BUY swaps for future SELL use.
                            // HD wallets use many addresses; this tells us where tokens actually went.
                            if (swapResult.tokenDeliveryAddress != null) {
                                session.knownTokenAddresses.add(swapResult.tokenDeliveryAddress!!)
                                logger.info("Stored token delivery address for session={}: {}",
                                    cookie?.take(6), swapResult.tokenDeliveryAddress!!.take(20) + "...")
                            }

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

                "add_liquidity", "remove_liquidity" -> {
                    val result = swapServiceV2.broadcastAndRecordLiquidity(
                        signedTxHex = txHex,
                        poolId = pending.poolId,
                        action = pending.action,
                        nexAmount = pending.nexAmount,
                        tokenAmount = pending.tokenAmount,
                        lpTokenAmount = pending.lpTokenAmount,
                        newNexReserve = pending.newNexReserve,
                        newTokenReserve = pending.newTokenReserve,
                    )

                    result
                        .onSuccess { lpResult ->
                            launch {
                                sessionManager.pushToBrowsers(session, WsBrowserMessage(
                                    type = "tx_signed",
                                    data = """{"action":"${pending.action}","txId":"${lpResult.txId}","poolId":${lpResult.poolId},"nexAmount":${lpResult.nexAmount},"tokenAmount":${lpResult.tokenAmount},"lpTokenAmount":${lpResult.lpTokenAmount}}""",
                                ))
                            }

                            call.respondText(
                                json.encodeToString(TxReturnResponse("ok", "Liquidity broadcast: ${lpResult.txId}")),
                                ContentType.Application.Json,
                                HttpStatusCode.OK,
                            )
                        }
                        .onFailure { error ->
                            logger.warn("Liquidity broadcast failed: {}", error.message)

                            launch {
                                sessionManager.pushToBrowsers(session, WsBrowserMessage(
                                    type = "tx_error",
                                    data = """{"action":"${pending.action}","error":"${error.message}"}""",
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
