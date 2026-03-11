package org.nexadex.indexer

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.nexadex.core.config.IndexerConfig
import org.nexadex.core.math.AmmMath
import org.nexadex.core.model.Trade
import org.nexadex.core.model.TradeDirection
import org.nexadex.core.model.TradeStatus
import org.nexadex.data.repository.PoolRepository
import org.nexadex.data.repository.TradeRepository
import org.nexadex.service.BlockNotification
import org.nexadex.service.EventBus
import org.nexadex.service.PoolService
import org.nexadex.service.PriceUpdate
import org.nexadex.service.SessionManager
import org.nexa.sdk.NexaSDK
import org.nexa.sdk.types.common.SdkResult
import org.nexa.sdk.types.primitives.Address
import org.nexa.sdk.types.primitives.TxId
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Watches the blockchain for pool-related events.
 *
 * - Subscribes to blocks for real-time notifications
 * - Monitors V2 pool UTXOs for swap detection
 */
class ChainIndexer(
    private val poolService: PoolService,
    private val poolRepo: PoolRepository,
    private val tradeRepo: TradeRepository,
    private val eventBus: EventBus,
    private val config: IndexerConfig,
    private val sessionManager: SessionManager? = null,
) {
    private val logger = LoggerFactory.getLogger(ChainIndexer::class.java)
    private val sdk get() = NexaSDK
    private var indexerJob: Job? = null

    fun start(scope: CoroutineScope) {
        indexerJob = scope.launch {
            logger.info("Chain indexer starting...")

            // Start block subscription
            launch { subscribeBlocks() }

            // Start V2 pool UTXO monitoring
            launch { v2PoolMonitorLoop() }
        }
    }

    fun stop() {
        indexerJob?.cancel()
        indexerJob = null
        logger.info("Chain indexer stopped")
    }

    private suspend fun subscribeBlocks() {
        try {
            when (val result = sdk.subscribe.subscribeBlocks()) {
                is SdkResult.Success -> {
                    logger.info("Subscribed to blocks")
                    result.value.collect { blockEvent ->
                        val height = blockEvent.blockHeight.height.toLong()
                        val hash = blockEvent.blockHash.hex
                        logger.debug("New block: height={}", height)
                        eventBus.emitBlock(
                            BlockNotification(
                                height = height,
                                hash = hash,
                                timestamp = blockEvent.timestamp.epochSeconds * 1000,
                            ),
                        )
                    }
                }
                is SdkResult.Failure -> {
                    logger.error("Failed to subscribe to blocks: {}", result.error)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Block subscription error", e)
        }
    }

    /**
     * V2 pool UTXO monitoring loop.
     * Polls V2 pool contract addresses for UTXO changes (new swaps).
     */
    private suspend fun v2PoolMonitorLoop() {
        delay(10_000)
        logger.info("V2 pool UTXO monitor starting...")

        while (currentCoroutineContext().isActive) {
            try {
                monitorV2Pools()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("V2 pool monitor error", e)
            }
            delay(15_000)
        }
    }

    /**
     * Monitor V2/V3 pools by querying the actual on-chain pool state via
     * getAmmDexPoolState (token.address.listunspent). This finds the pool UTXO
     * by matching the trade group ID — reliable regardless of txId/txIdem format.
     *
     * If on-chain state differs from DB, syncs the DB and records detected trades.
     */
    private suspend fun monitorV2Pools() {
        val v2Pools = poolRepo.findActiveV2Pools()
        for (pool in v2Pools) {
            try {
                // Skip pools with zeroed reserves (dead pools)
                if (pool.nexReserve <= 0L && pool.tokenReserve <= 0L) {
                    continue
                }
                if (pool.lpGroupIdHex.isNullOrBlank()) {
                    continue
                }

                val instance = try {
                    poolService.getContractInstance(pool)
                } catch (e: Exception) {
                    logger.warn("Cannot get contract instance for pool {}: {}", pool.poolId, e.message)
                    continue
                }

                // Query on-chain pool state via token.address.listunspent
                val state = when (val r = sdk.contract.getAmmDexPoolState(
                    instance = instance,
                    tradeGroupId = pool.tokenGroupIdHex,
                    lpGroupId = pool.lpGroupIdHex!!,
                    initialLpSupply = pool.initialLpSupply,
                )) {
                    is SdkResult.Success -> r.value
                    is SdkResult.Failure -> {
                        logger.warn("Cannot query pool state for pool {}: {}", pool.poolId, r.error)
                        continue
                    }
                }

                val onChainNex = state.nexReserve.satoshis
                val onChainTokens = state.tokenReserve
                val onChainOutpoint = state.poolOutpointHash

                if (onChainOutpoint == null) {
                    logger.warn("V2 pool {} pool UTXO not found on chain", pool.poolId)
                    continue
                }

                // Always sync the outpoint hash if it differs (DB may have txIdem instead of outpoint hash)
                val outpointChanged = pool.poolUtxoTxId != onChainOutpoint
                if (outpointChanged && onChainNex == pool.nexReserve && onChainTokens == pool.tokenReserve) {
                    logger.info("V2 pool {} outpoint hash sync: {} -> {}",
                        pool.poolId, pool.poolUtxoTxId?.take(12), onChainOutpoint.take(12))
                    poolRepo.updatePoolUtxoAndReserves(pool.poolId, onChainOutpoint, 0, onChainNex, onChainTokens)
                }

                // Check if reserves changed (swap happened)
                if (onChainNex != pool.nexReserve || onChainTokens != pool.tokenReserve) {
                    logger.info(
                        "V2 pool {} state changed: nex {} -> {}, tokens {} -> {}, outpoint={}",
                        pool.poolId, pool.nexReserve, onChainNex, pool.tokenReserve, onChainTokens, onChainOutpoint.take(12),
                    )

                    // Determine trade direction from reserve changes
                    val nexDelta = onChainNex - pool.nexReserve
                    val tokenDelta = onChainTokens - pool.tokenReserve

                    // Record as trade if it looks like a swap (one reserve up, other down)
                    if ((nexDelta > 0 && tokenDelta < 0) || (nexDelta < 0 && tokenDelta > 0)) {
                        val direction = if (nexDelta > 0) TradeDirection.BUY else TradeDirection.SELL
                        val amountIn = if (direction == TradeDirection.BUY) nexDelta else -tokenDelta
                        val amountOut = if (direction == TradeDirection.BUY) -tokenDelta else -nexDelta

                        val trade = tradeRepo.insert(
                            Trade(
                                poolId = pool.poolId,
                                direction = direction,
                                amountIn = amountIn,
                                amountOut = amountOut,
                                price = if (amountOut > 0) amountIn.toDouble() / amountOut.toDouble() else 0.0,
                                nexReserveAfter = onChainNex,
                                tokenReserveAfter = onChainTokens,
                                txId = onChainOutpoint, // Use outpoint hash as reference
                                status = TradeStatus.CONFIRMED,
                            ),
                        )

                        logger.info(
                            "V2 swap detected: pool={}, dir={}, in={}, out={}, outpoint={}",
                            pool.poolId, direction, amountIn, amountOut, onChainOutpoint.take(12),
                        )

                        eventBus.emitTrade(trade)
                        val spotPrice = AmmMath.spotPrice(onChainNex, onChainTokens) ?: 0.0
                        eventBus.emitPriceUpdate(
                            PriceUpdate(
                                poolId = pool.poolId,
                                spotPrice = spotPrice,
                                nexReserve = onChainNex,
                                tokenReserve = onChainTokens,
                                lastTradeDirection = direction.name,
                                lastTradeAmountIn = amountIn,
                                lastTradeAmountOut = amountOut,
                            ),
                        )
                    }

                    // Update pool UTXO and reserves in DB.
                    poolRepo.updatePoolUtxoAndReserves(pool.poolId, onChainOutpoint, 0, onChainNex, onChainTokens)

                    // Chain reconciliation: if any session has a pending TDPP that matches
                    // the new on-chain reserves, auto-complete it. Handles the case where
                    // Wally's callback failed (mobile network, timeout).
                    sessionManager?.let { sm ->
                        try {
                            val serverFqdn = System.getenv("NEXADEX_SERVER_FQDN") ?: "localhost:9090"
                            val reconciled = sm.reconcilePendingTdpp(pool.poolId, onChainNex, onChainTokens, serverFqdn)
                            if (reconciled != null) {
                                logger.info("Auto-reconciled pending TDPP for pool {} via chain indexer", pool.poolId)
                            }
                        } catch (e: Exception) {
                            logger.warn("TDPP reconciliation failed for pool {}: {}", pool.poolId, e.message)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error monitoring V2 pool {}", pool.poolId, e)
            }
        }
    }

    /**
     * Compute the Nexa outpoint hash from txIdem hex and vout.
     * outpointHash = SHA256(txIdem_LE_bytes || vout_uint32_LE)
     */
    private fun computeOutpointHash(txIdemHex: String, vout: Int): String {
        val txIdemBytes = hexToBytes(txIdemHex).reversedArray() // big-endian hex → little-endian bytes
        val preimage = ByteArray(36)
        txIdemBytes.copyInto(preimage, 0)
        preimage[32] = (vout and 0xFF).toByte()
        preimage[33] = ((vout ushr 8) and 0xFF).toByte()
        preimage[34] = ((vout ushr 16) and 0xFF).toByte()
        preimage[35] = ((vout ushr 24) and 0xFF).toByte()
        val hash = MessageDigest.getInstance("SHA-256").digest(preimage)
        // Reverse to big-endian display order (matches Rostrum's outpoint_hash convention)
        return bytesToHex(hash.reversedArray())
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
