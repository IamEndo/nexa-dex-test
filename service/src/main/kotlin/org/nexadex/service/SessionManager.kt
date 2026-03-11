package org.nexadex.service

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages browser sessions and Wally wallet connections.
 *
 * Communication model (NiftyArt-proven pattern):
 * - Browser ↔ Server: WebSocket (real-time push)
 * - Server ↔ Wally:   HTTP Long Polling (Wally polls /_lp)
 * - Authentication:    NexID challenge-response
 * - Transactions:      TDPP protocol (server builds partial tx, Wally signs)
 */
class SessionManager {
    private val logger = LoggerFactory.getLogger(SessionManager::class.java)
    private val sessions = ConcurrentHashMap<String, DexSession>()
    private val random = SecureRandom()
    private val json = Json { encodeDefaults = true }

    companion object {
        private const val COOKIE_LENGTH = 16
        private const val WALLET_POLL_TIMEOUT_MS = 5000L
        private const val CLEANUP_INTERVAL_MS = 60_000L
        private const val WALLET_MONITOR_INTERVAL_MS = 10_000L  // Check wallet liveness every 10s
        private const val WALLET_LIVENESS_TIMEOUT_MS = 15_000L  // Wallet dead if no poll for 15s
        private const val PENDING_TDPP_TIMEOUT_MS = 30_000L    // Clear stale pendingTdpp after 30s
        private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }

    fun createSession(): DexSession {
        val cookie = generateCookie()
        val session = DexSession(sessionId = cookie)
        sessions[cookie] = session
        logger.debug("Session created: {}", cookie)
        return session
    }

    fun getSession(cookie: String): DexSession? {
        val session = sessions[cookie] ?: return null
        if (session.expiresAt.isBefore(Instant.now())) {
            destroySession(cookie)
            return null
        }
        return session
    }

    fun destroySession(cookie: String) {
        val session = sessions.remove(cookie) ?: return
        session.walletChannel.close()
        session.walletConnected = false
        // Notify destroy listeners (WS connections close themselves)
        val listeners = synchronized(session.destroyListeners) { session.destroyListeners.toList() }
        listeners.forEach { it() }
        logger.debug("Session destroyed: {}", cookie)
    }

    fun pushToWallet(session: DexSession, message: String) {
        session.walletChannel.trySend(message)
    }

    suspend fun receiveFromWalletChannel(session: DexSession): String? {
        return try {
            withTimeoutOrNull(WALLET_POLL_TIMEOUT_MS) {
                session.walletChannel.receive()
            }
        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            null // Session was destroyed while waiting
        }
    }

    /**
     * Push a message to all registered browser message handlers.
     * Automatically removes dead/stale handlers that return false.
     */
    suspend fun pushToBrowsers(session: DexSession, message: WsBrowserMessage) {
        val text = json.encodeToString(message)
        val handlers = synchronized(session.browserMessageHandlers) {
            session.browserMessageHandlers.toList()
        }
        logger.info("pushToBrowsers: type={}, handlers={}, session={}", message.type, handlers.size, session.sessionId.take(6))
        val deadHandlers = mutableListOf<suspend (String) -> Boolean>()
        for (handler in handlers) {
            try {
                val ok = handler(text)
                if (ok) {
                    logger.info("pushToBrowsers: sent OK to handler")
                } else {
                    logger.info("pushToBrowsers: handler reported dead, will remove")
                    deadHandlers.add(handler)
                }
            } catch (e: Exception) {
                logger.warn("pushToBrowsers: handler failed: {}, will remove", e.message)
                deadHandlers.add(handler)
            }
        }
        // Clean up dead/stale handlers
        if (deadHandlers.isNotEmpty()) {
            synchronized(session.browserMessageHandlers) {
                session.browserMessageHandlers.removeAll(deadHandlers.toSet())
            }
            logger.info("pushToBrowsers: removed {} dead handlers", deadHandlers.size)
        }
    }

    /**
     * Register a browser message handler (called from WS layer in api module).
     * Handler returns true if send succeeded, false if WebSocket is dead.
     * Returns a removal function.
     */
    fun registerBrowserHandler(session: DexSession, handler: suspend (String) -> Boolean): () -> Unit {
        synchronized(session.browserMessageHandlers) {
            session.browserMessageHandlers.add(handler)
        }
        return {
            synchronized(session.browserMessageHandlers) {
                session.browserMessageHandlers.remove(handler)
            }
        }
    }

    fun startCleanup(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                val now = Instant.now()
                val expired = sessions.entries.filter { it.value.expiresAt.isBefore(now) }
                for ((cookie, _) in expired) {
                    destroySession(cookie)
                }
                if (expired.isNotEmpty()) {
                    logger.debug("Cleaned {} expired sessions, {} active", expired.size, sessions.size)
                }
            }
        }
    }

    /**
     * Background monitor that checks wallet liveness every 10 seconds.
     * Like NiftyArt's walletSessionMonitor — re-affirms or marks wallet dead.
     */
    fun startWalletMonitor(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(WALLET_MONITOR_INTERVAL_MS)
                for ((_, session) in sessions) {
                    val wasConnected = session.walletConnected
                    val isAlive = checkWalletAlive(session)
                    // Only notify browsers on state CHANGE (connected → disconnected)
                    if (!isAlive && wasConnected) {
                        logger.info("Wallet liveness expired for session={}", session.sessionId.take(6))
                        pushToBrowsers(session, WsBrowserMessage(
                            type = "wallet_connection",
                            data = """{"status":"disconnected"}""",
                        ))
                    }

                    // Clear stale pendingTdpp (Wally crashed or user denied)
                    val pending = session.pendingTdpp
                    if (pending != null) {
                        val pendingAge = Duration.between(pending.createdAt, Instant.now()).toMillis()
                        if (pendingAge > PENDING_TDPP_TIMEOUT_MS) {
                            session.pendingTdpp = null
                            logger.info("Cleared stale pendingTdpp for session={} (age={}ms)", session.sessionId.take(6), pendingAge)
                            // Notify browsers so frontend can exit pending state
                            pushToBrowsers(session, WsBrowserMessage(
                                type = "tx_error",
                                data = """{"action":"${pending.action}","error":"Wallet confirmation timed out"}""",
                            ))
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if wallet is still alive (polled within WALLET_LIVENESS_TIMEOUT_MS).
     * Like NiftyArt's checkWalletAlive().
     */
    private fun checkWalletAlive(session: DexSession): Boolean {
        if (!session.walletConnected) return false
        val elapsed = Duration.between(session.lastWalletPoll, Instant.now()).toMillis()
        if (elapsed > WALLET_LIVENESS_TIMEOUT_MS) {
            session.walletConnected = false
            return false
        }
        return true
    }

    /**
     * Chain-based reconciliation: when ChainIndexer detects a pool state change,
     * find any session with a matching pendingTdpp and auto-complete it.
     * Handles the case where Wally's GET /tx callback fails (mobile network, timeout).
     *
     * Returns the reconciled session, or null if no match.
     */
    suspend fun reconcilePendingTdpp(
        poolId: Int,
        onChainNexReserve: Long,
        onChainTokenReserve: Long,
        serverFqdn: String,
    ): DexSession? {
        for ((_, session) in sessions) {
            val pending = session.pendingTdpp ?: continue
            if (pending.poolId != poolId) continue

            // Match: on-chain reserves equal the expected post-tx reserves
            if (pending.newNexReserve == onChainNexReserve &&
                pending.newTokenReserve == onChainTokenReserve
            ) {
                session.pendingTdpp = null
                logger.info(
                    "Chain reconciled pendingTdpp: session={}, pool={}, action={}",
                    session.sessionId.take(6), poolId, pending.action,
                )

                pushToBrowsers(session, WsBrowserMessage(
                    type = "tx_signed",
                    data = """{"action":"${pending.action}","poolId":$poolId,"reconciled":true,"direction":"${pending.direction ?: ""}"}""",
                ))

                // Invalidate stale wallet assets and request refresh
                session.assetsLoaded = false
                session.walletAssets.clear()
                if (session.walletConnected) {
                    val assetUri = "tdpp://$serverFqdn/assets?chain=nexa&af=f1f1&rproto=https&cookie=${session.sessionId}"
                    pushToWallet(session, assetUri)
                    logger.info("Requested wallet asset refresh after reconciliation for session={}", session.sessionId.take(6))
                }

                return session
            }
        }
        return null
    }

    fun activeSessionCount(): Int = sessions.size

    private fun generateCookie(): String {
        val sb = StringBuilder(COOKIE_LENGTH)
        for (i in 0 until COOKIE_LENGTH) {
            sb.append(CHARS[random.nextInt(CHARS.length)])
        }
        return sb.toString()
    }
}

/**
 * Represents a user session linking a browser and optionally a Wally wallet.
 */
data class DexSession(
    val sessionId: String,
    var nexAddress: String? = null,
    @Volatile var walletConnected: Boolean = false,
    @Volatile var lastWalletPoll: Instant = Instant.EPOCH,

    // Wally long-poll channel
    val walletChannel: Channel<String> = Channel(Channel.UNLIMITED),

    // Browser WS message handlers — return true if sent OK, false if dead (will be cleaned up)
    val browserMessageHandlers: MutableList<suspend (String) -> Boolean> = mutableListOf(),

    // Destroy listeners (for WS cleanup)
    val destroyListeners: MutableList<() -> Unit> = mutableListOf(),

    // TDPP transaction state — volatile for cross-thread visibility
    @Volatile var pendingTdpp: PendingTdpp? = null,

    // Known addresses where user's tokens may reside (from BUY delivery, /_share, etc.)
    // Used for SELL swaps to find token UTXOs across multiple HD wallet addresses.
    val knownTokenAddresses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),

    // Wallet asset UTXOs from TDPP /assets query (NiftyArt pattern).
    // Wally scans ALL HD addresses and returns every grouped UTXO it owns.
    // Key = outpointHash, Value = WalletAssetUtxo with prevout script, amount, etc.
    val walletAssets: MutableList<WalletAssetUtxo> = java.util.Collections.synchronizedList(mutableListOf()),
    @Volatile var assetsLoaded: Boolean = false,

    val createdAt: Instant = Instant.now(),
    var expiresAt: Instant = Instant.now().plusSeconds(7200),
)

/**
 * Pending TDPP transaction waiting for Wally's signed response.
 */
data class PendingTdpp(
    val poolId: Int,
    val action: String,
    val direction: String?,
    val amountIn: Long,
    val expectedAmountOut: Long,
    val newNexReserve: Long,
    val newTokenReserve: Long,
    val partialTxHex: String,
    // Liquidity-specific fields
    val nexAmount: Long = 0,
    val tokenAmount: Long = 0,
    val lpTokenAmount: Long = 0,
    val createdAt: Instant = Instant.now(),
)

/**
 * A grouped UTXO reported by Wally wallet via the /assets TDPP endpoint.
 * Matches Wally's TricklePayAssetInfo format.
 */
data class WalletAssetUtxo(
    val outpointHash: String,
    val amount: Long,
    val prevoutHex: String,
    val groupIdHex: String? = null,
    val tokenAmount: Long = 0,
)

/**
 * Wally's asset list response (matches TricklePayAssetList in Wally/NiftyArt).
 */
@Serializable
data class TricklePayAssetInfo(
    val outpointHash: String,
    val amt: Long,
    val prevout: String,
    val proof: String? = null,
)

@Serializable
data class TricklePayAssetList(
    val assets: List<TricklePayAssetInfo>,
)

@Serializable
data class WsBrowserMessage(
    val type: String,
    val data: String? = null,
)
