package org.nexadex.service

import org.nexadex.core.config.TradingConfig
import org.nexadex.core.error.DexError
import org.nexadex.core.error.DexResult
import org.nexadex.core.math.AmmMath
import org.nexadex.core.model.*
import org.nexadex.data.repository.PoolRepository
import org.nexadex.data.repository.TradeRepository
import org.nexa.sdk.NexaSDK
import org.nexa.sdk.types.common.SdkResult
import org.nexa.sdk.types.contract.AmmDexPoolState
import org.nexa.sdk.types.wallet.Mnemonic
import org.nexa.sdk.types.wallet.Network
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * V2 swap service for permissionless DEX.
 *
 * Uses the SDK's executeAmmDexSwap which handles all UTXO selection,
 * transaction building, signing, and broadcasting internally.
 */
class SwapServiceV2(
    private val poolRepo: PoolRepository,
    private val tradeRepo: TradeRepository,
    private val tradingConfig: TradingConfig,
    private val eventBus: EventBus,
    private val poolService: PoolService,
) {
    private val logger = LoggerFactory.getLogger(SwapServiceV2::class.java)

    /**
     * Returns current pool state including UTXO outpoint, contract info, and LP state.
     */
    suspend fun getPoolState(poolId: Int): DexResult<PoolState> {
        val pool = poolRepo.findById(poolId)
            ?: return DexResult.failure(DexError.PoolNotFound(poolId))

        val spotPrice = if (pool.tokenReserve > 0) {
            pool.nexReserve.toDouble() / pool.tokenReserve.toDouble()
        } else 0.0

        // Query on-chain LP state
        var lpReserveBalance = 0L
        var lpInCirculation = 0L
        try {
            val instance = poolService.getContractInstance(pool)
            when (val r = NexaSDK.contract.getAmmDexPoolState(
                instance = instance,
                tradeGroupId = pool.tokenGroupIdHex,
                lpGroupId = pool.lpGroupIdHex,
                initialLpSupply = pool.initialLpSupply,
            )) {
                is SdkResult.Success -> {
                    lpReserveBalance = r.value.lpReserveBalance
                    lpInCirculation = r.value.lpInCirculation
                }
                is SdkResult.Failure -> {
                    logger.warn("Could not query LP state for pool {}: {}", poolId, r.error)
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not query LP state for pool {}: {}", poolId, e.message)
        }

        return DexResult.success(
            PoolState(
                poolId = pool.poolId,
                tokenGroupIdHex = pool.tokenGroupIdHex,
                lpGroupIdHex = pool.lpGroupIdHex,
                contractAddress = pool.contractAddress,
                contractVersion = pool.contractVersion,
                status = pool.status.name,
                nexReserve = pool.nexReserve,
                tokenReserve = pool.tokenReserve,
                spotPrice = spotPrice,
                poolUtxoTxId = pool.poolUtxoTxId,
                poolUtxoVout = pool.poolUtxoVout,
                initialLpSupply = pool.initialLpSupply,
                lpReserveBalance = lpReserveBalance,
                lpInCirculation = lpInCirculation,
                lastUpdated = pool.updatedAt,
            ),
        )
    }

    /**
     * Computes swap output parameters for building a transaction.
     */
    fun buildSwapParams(
        poolId: Int,
        direction: TradeDirection,
        amountIn: Long,
        maxSlippageBps: Int = tradingConfig.maxSlippageBps,
    ): DexResult<SwapBuildParams> {
        val pool = poolRepo.findById(poolId)
            ?: return DexResult.failure(DexError.PoolNotFound(poolId))

        if (pool.status != PoolStatus.ACTIVE) {
            return DexResult.failure(DexError.PoolNotActive(poolId, pool.status.name))
        }
        if (pool.poolUtxoTxId == null) {
            return DexResult.failure(DexError.InvalidOperation("Pool $poolId has no tracked UTXO"))
        }
        if (amountIn <= 0) {
            return DexResult.failure(DexError.TradeTooSmall(amountIn, 1))
        }

        val (amountOut, priceImpactBps) = when (direction) {
            TradeDirection.SELL -> {
                val out = AmmMath.computeSellOutput(amountIn, pool.nexReserve, pool.tokenReserve)
                    ?: return DexResult.failure(DexError.InsufficientLiquidity(amountIn, pool.tokenReserve))
                val impact = AmmMath.computePriceImpactBps(amountIn, out, pool.tokenReserve, pool.nexReserve) ?: 0
                Pair(out, impact)
            }
            TradeDirection.BUY -> {
                val out = AmmMath.computeBuyOutput(amountIn, pool.nexReserve, pool.tokenReserve)
                    ?: return DexResult.failure(DexError.InsufficientLiquidity(amountIn, pool.nexReserve))
                val impact = AmmMath.computePriceImpactBps(amountIn, out, pool.nexReserve, pool.tokenReserve) ?: 0
                Pair(out, impact)
            }
        }

        val minimumReceived = AmmMath.computeMinimumReceived(amountOut, maxSlippageBps)
        val price = if (amountIn > 0) amountOut.toDouble() / amountIn.toDouble() else 0.0

        val (newNexReserve, newTokenReserve) = when (direction) {
            TradeDirection.SELL -> Pair(pool.nexReserve - amountOut, pool.tokenReserve + amountIn)
            TradeDirection.BUY -> Pair(pool.nexReserve + amountIn, pool.tokenReserve - amountOut)
        }

        return DexResult.success(
            SwapBuildParams(
                poolId = poolId,
                direction = direction,
                amountIn = amountIn,
                amountOut = amountOut,
                price = price,
                priceImpactBps = priceImpactBps,
                minimumReceived = minimumReceived,
                newPoolNex = newNexReserve,
                newPoolTokens = newTokenReserve,
                poolUtxoTxId = pool.poolUtxoTxId!!,
                poolUtxoVout = pool.poolUtxoVout!!,
                estimatedFee = 2500L,
            ),
        )
    }

    /**
     * Broadcasts a user-signed raw transaction hex via Rostrum (Electrum protocol).
     * Returns the txidem on success.
     */
    suspend fun broadcastTransaction(signedTxHex: String, poolId: Int): DexResult<String> {
        return try {
            when (val result = NexaSDK.transaction.broadcastRawHex(signedTxHex)) {
                is SdkResult.Success -> {
                    val txIdem = result.value
                    logger.info("Swap relayed for pool $poolId: $txIdem")
                    DexResult.success(txIdem)
                }
                is SdkResult.Failure -> {
                    logger.warn("Relay failed for pool $poolId: {}", result.error)
                    // Decode tx to diagnose which input is conflicting
                    logTxInputDiagnostics(signedTxHex, poolId)
                    DexResult.failure(DexError.BroadcastFailed("Relay rejected: ${result.error}"))
                }
            }
        } catch (e: Exception) {
            logger.error("Relay exception for pool $poolId", e)
            DexResult.failure(DexError.BroadcastFailed("Relay error: ${e.message}"))
        }
    }

    /**
     * Decode a signed transaction and log all input outpoint hashes for debugging.
     * Also queries Rostrum for the live pool state to detect stale data.
     */
    private suspend fun logTxInputDiagnostics(signedTxHex: String, poolId: Int) {
        try {
            // Decode the signed transaction
            when (val decoded = NexaSDK.transaction.decode(signedTxHex)) {
                is SdkResult.Success -> {
                    val tx = decoded.value
                    logger.warn("=== TX DIAGNOSTIC for pool {} ===", poolId)
                    logger.warn("  txIdem={}, inputs={}, outputs={}, size={}",
                        tx.txIdem.hex, tx.inputCount, tx.outputCount, tx.size)
                    tx.inputs.forEachIndexed { idx, input ->
                        logger.warn("  Input[{}]: outpointHash={}, amount={} sat, type={}",
                            idx,
                            input.outpointHash ?: "N/A",
                            input.amount.satoshis,
                            input.inputType,
                        )
                    }
                    tx.outputs.forEachIndexed { idx, output ->
                        logger.warn("  Output[{}]: amount={} sat, type={}",
                            idx, output.amount.satoshis, output.outputType)
                    }
                }
                is SdkResult.Failure -> {
                    logger.warn("Could not decode TX for diagnostics: {}", decoded.error)
                }
            }

            // Query Rostrum for live pool state
            val pool = poolRepo.findById(poolId) ?: return
            try {
                val instance = poolService.getContractInstance(pool)
                when (val r = NexaSDK.contract.getAmmDexPoolState(
                    instance = instance,
                    tradeGroupId = pool.tokenGroupIdHex,
                    lpGroupId = pool.lpGroupIdHex,
                    initialLpSupply = pool.initialLpSupply,
                )) {
                    is SdkResult.Success -> {
                        val state = r.value
                        logger.warn("  Live Rostrum state: outpoint={}, nex={}, tokens={}",
                            state.poolOutpointHash?.take(16), state.nexReserve.satoshis, state.tokenReserve)
                        logger.warn("  DB state: outpoint={}, nex={}, tokens={}",
                            pool.poolUtxoTxId?.take(16), pool.nexReserve, pool.tokenReserve)
                    }
                    is SdkResult.Failure -> {
                        logger.warn("  Could not query live pool state: {}", r.error)
                    }
                }
            } catch (e: Exception) {
                logger.warn("  Could not query live pool state: {}", e.message)
            }
            logger.warn("=== END TX DIAGNOSTIC ===")
        } catch (e: Exception) {
            logger.warn("TX diagnostic failed: {}", e.message)
        }
    }

    /**
     * Build a partial swap transaction for TDPP (Wally wallet signing).
     * Returns the contract side of the tx (pool input + expected outputs),
     * without user inputs. Wally adds funding inputs, signs, and returns
     * the complete transaction.
     */
    suspend fun prepareSwapTdpp(
        poolId: Int,
        direction: TradeDirection,
        amountIn: Long,
        maxSlippageBps: Int = tradingConfig.maxSlippageBps,
        userAddress: String? = null,
    ): DexResult<SwapTdppResult> {
        val pool = poolRepo.findById(poolId)
            ?: return DexResult.failure(DexError.PoolNotFound(poolId))

        if (pool.status != PoolStatus.ACTIVE) {
            return DexResult.failure(DexError.PoolNotActive(poolId, pool.status.name))
        }
        if (pool.poolUtxoTxId == null) {
            return DexResult.failure(DexError.InvalidOperation("Pool $poolId has no tracked UTXO"))
        }
        if (amountIn <= 0) {
            return DexResult.failure(DexError.TradeTooSmall(amountIn, 1))
        }

        // Get contract instance
        val instance = try {
            poolService.getContractInstance(pool)
        } catch (e: Exception) {
            return DexResult.failure(DexError.InternalError("Failed to get contract instance: ${e.message}"))
        }

        // Build partial transaction via SDK using DB-tracked pool UTXO.
        // This bypasses the SDK's Rostrum query which can return stale UTXOs
        // when the previous swap tx is still unconfirmed in the mempool.
        // The SDK computes outpointHash = SHA256(txIdem_LE || vout_uint32_LE) internally.
        val sdkDirection = org.nexa.sdk.types.contract.TradeDirection.valueOf(direction.name)

        logger.info("prepareSwapTdpp: pool={}, dir={}, poolUtxoTxId={}, vout={}, nexReserve={}, tokenReserve={}",
            poolId, direction, pool.poolUtxoTxId, pool.poolUtxoVout, pool.nexReserve, pool.tokenReserve)

        // For SELL swaps with a known user address, use the overload that includes
        // user token inputs in the partial tx (avoids Wally FUND_GROUPS issue).
        val partialResult = if (direction == TradeDirection.SELL && userAddress != null) {
            logger.info("prepareSwapTdpp: SELL with user token inputs, userAddr={}", userAddress.take(20) + "...")
            NexaSDK.contract.buildAmmDexSwapPartial(
                instance = instance,
                direction = sdkDirection,
                amountIn = amountIn,
                tradeGroupId = pool.tokenGroupIdHex,
                knownPoolOutpointHash = pool.poolUtxoTxId!!,
                knownNexReserve = pool.nexReserve,
                knownTokenReserve = pool.tokenReserve,
                userAddress = userAddress,
            )
        } else {
            NexaSDK.contract.buildAmmDexSwapPartial(
                instance = instance,
                direction = sdkDirection,
                amountIn = amountIn,
                tradeGroupId = pool.tokenGroupIdHex,
                knownPoolOutpointHash = pool.poolUtxoTxId!!,
                knownNexReserve = pool.nexReserve,
                knownTokenReserve = pool.tokenReserve,
            )
        }

        return when (partialResult) {
            is SdkResult.Success -> {
                val r = partialResult.value
                val price = if (r.amountIn > 0) r.expectedAmountOut.toDouble() / r.amountIn.toDouble() else 0.0

                // Compute price impact using our AMM math
                val priceImpactBps = when (direction) {
                    TradeDirection.SELL -> AmmMath.computePriceImpactBps(amountIn, r.expectedAmountOut, pool.tokenReserve, pool.nexReserve) ?: 0
                    TradeDirection.BUY -> AmmMath.computePriceImpactBps(amountIn, r.expectedAmountOut, pool.nexReserve, pool.tokenReserve) ?: 0
                }
                val minimumReceived = AmmMath.computeMinimumReceived(r.expectedAmountOut, maxSlippageBps)

                logger.info("TDPP partial tx built: pool={}, dir={}, in={}, out={}, txLen={}",
                    poolId, direction, amountIn, r.expectedAmountOut, r.partialTxHex.length)

                DexResult.success(
                    SwapTdppResult(
                        partialTxHex = r.partialTxHex,
                        poolId = poolId,
                        direction = direction,
                        amountIn = r.amountIn,
                        amountOut = r.expectedAmountOut,
                        price = price,
                        priceImpactBps = priceImpactBps,
                        minimumReceived = minimumReceived,
                        newPoolNex = r.newPoolNex,
                        newPoolTokens = r.newPoolTokens,
                        totalInputSatoshis = r.totalInputSatoshis,
                    ),
                )
            }
            is SdkResult.Failure -> {
                logger.error("TDPP partial tx build failed: pool={}, error={}", poolId, partialResult.error)
                DexResult.failure(DexError.InternalError("Failed to build partial tx: ${partialResult.error}"))
            }
        }
    }

    /**
     * Broadcast a Wally-signed swap transaction and record the trade.
     * Called when Wally returns a signed tx via the /tx callback.
     */
    suspend fun broadcastAndRecordSwap(
        signedTxHex: String,
        poolId: Int,
        direction: TradeDirection,
        amountIn: Long,
        expectedAmountOut: Long,
        newNexReserve: Long,
        newTokenReserve: Long,
    ): DexResult<SwapResult> {
        val broadcastResult = broadcastTransaction(signedTxHex, poolId)
        if (broadcastResult.isFailure) {
            return DexResult.failure(broadcastResult.errorOrNull()!!)
        }

        val txIdem = broadcastResult.getOrNull()!!
        val price = if (amountIn > 0) expectedAmountOut.toDouble() / amountIn.toDouble() else 0.0

        // Compute the Nexa outpoint hash: SHA256(txIdem_LE || vout_uint32_LE)
        // This is what Rostrum uses to identify UTXOs, and what the SDK needs
        // to build the next partial transaction.
        val outpointHash = computeOutpointHash(txIdem, 0)
        logger.info("Swap broadcast success: txIdem={}, outpointHash={}", txIdem, outpointHash.take(12))

        // Store the outpoint hash (not txIdem) as poolUtxoTxId — consistent with
        // how the indexer stores Rostrum's outpoint_hash for pool UTXO tracking.
        poolRepo.updatePoolUtxoAndReserves(poolId, outpointHash, 0, newNexReserve, newTokenReserve)

        // Record trade (use txIdem for display/tracking)
        try {
            val trade = Trade(
                poolId = poolId,
                direction = direction,
                amountIn = amountIn,
                amountOut = expectedAmountOut,
                price = price,
                nexReserveAfter = newNexReserve,
                tokenReserveAfter = newTokenReserve,
                txId = txIdem,
                status = TradeStatus.CONFIRMED,
            )
            tradeRepo.insert(trade)
            eventBus.emitTrade(trade)
        } catch (e: Exception) {
            logger.warn("Failed to record trade for txIdem={}: {}", txIdem, e.message)
        }

        logger.info("TDPP swap broadcast+recorded: pool={}, dir={}, txIdem={}", poolId, direction, txIdem)

        return DexResult.success(
            SwapResult(
                txId = txIdem,
                poolId = poolId,
                direction = direction,
                amountIn = amountIn,
                amountOut = expectedAmountOut,
                price = price,
            ),
        )
    }

    /**
     * Execute a swap using the user's mnemonic.
     * SDK handles all UTXO selection, tx building, signing, and broadcasting.
     */
    suspend fun executeSwap(
        poolId: Int,
        direction: TradeDirection,
        amountIn: Long,
        userMnemonic: String,
        maxSlippageBps: Int = tradingConfig.maxSlippageBps,
    ): DexResult<SwapResult> {
        val pool = poolRepo.findById(poolId)
            ?: return DexResult.failure(DexError.PoolNotFound(poolId))

        if (pool.status != PoolStatus.ACTIVE) {
            return DexResult.failure(DexError.PoolNotActive(poolId, pool.status.name))
        }
        if (pool.poolUtxoTxId == null) {
            return DexResult.failure(DexError.InvalidOperation("Pool $poolId has no tracked UTXO"))
        }
        if (amountIn <= 0) {
            return DexResult.failure(DexError.TradeTooSmall(amountIn, 1))
        }

        // Compute expected swap output
        val (amountOut, priceImpactBps) = when (direction) {
            TradeDirection.SELL -> {
                val out = AmmMath.computeSellOutput(amountIn, pool.nexReserve, pool.tokenReserve)
                    ?: return DexResult.failure(DexError.InsufficientLiquidity(amountIn, pool.tokenReserve))
                val impact = AmmMath.computePriceImpactBps(amountIn, out, pool.tokenReserve, pool.nexReserve) ?: 0
                Pair(out, impact)
            }
            TradeDirection.BUY -> {
                val out = AmmMath.computeBuyOutput(amountIn, pool.nexReserve, pool.tokenReserve)
                    ?: return DexResult.failure(DexError.InsufficientLiquidity(amountIn, pool.nexReserve))
                val impact = AmmMath.computePriceImpactBps(amountIn, out, pool.nexReserve, pool.tokenReserve) ?: 0
                Pair(out, impact)
            }
        }

        val price = if (amountIn > 0) amountOut.toDouble() / amountIn.toDouble() else 0.0
        val (newPoolNex, newPoolTokens) = when (direction) {
            TradeDirection.SELL -> Pair(pool.nexReserve - amountOut, pool.tokenReserve + amountIn)
            TradeDirection.BUY -> Pair(pool.nexReserve + amountIn, pool.tokenReserve - amountOut)
        }

        // Create temporary wallet
        val mnemonic = when (val r = Mnemonic.parse(userMnemonic)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> return DexResult.failure(DexError.InvalidOperation("Invalid mnemonic"))
        }
        val wallet = when (val r = NexaSDK.wallet.createFromMnemonic(mnemonic, Network.MAINNET)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> return DexResult.failure(DexError.InternalError("Failed to create wallet: ${r.error}"))
        }

        return try {
            // Get contract instance (re-instantiates from group IDs if blob is empty)
            val instance = try {
                poolService.getContractInstance(pool)
            } catch (e: Exception) {
                return DexResult.failure(DexError.InternalError("Failed to get contract instance: ${e.message}"))
            }

            logger.info("Swap: pool={}, dir={}, in={}, expectedOut={}", pool.poolId, direction, amountIn, amountOut)

            // SDK handles everything: UTXO selection, tx building, signing, broadcasting
            val swapResult = when (direction) {
                TradeDirection.SELL -> NexaSDK.contract.executeAmmDexSwap(
                    wallet = wallet,
                    instance = instance,
                    tokenAmountIn = amountIn,
                    tradeGroupId = pool.tokenGroupIdHex,
                    lpGroupId = pool.lpGroupIdHex,
                    initialLpSupply = pool.initialLpSupply,
                )
                TradeDirection.BUY -> NexaSDK.contract.executeAmmDexSwapBuy(
                    wallet = wallet,
                    instance = instance,
                    nexAmountIn = amountIn,
                    tradeGroupId = pool.tokenGroupIdHex,
                    lpGroupId = pool.lpGroupIdHex,
                    initialLpSupply = pool.initialLpSupply,
                )
            }

            when (swapResult) {
                is SdkResult.Success -> {
                    val txId = swapResult.value.txIdem.hex
                    logger.info("Swap succeeded: pool={}, txIdem={}", pool.poolId, txId)

                    // Compute outpoint hash from txIdem for correct UTXO tracking
                    val outpointHash = computeOutpointHash(txId, 0)
                    logger.info("Swap outpointHash={}", outpointHash.take(12))
                    poolRepo.updatePoolUtxoAndReserves(pool.poolId, outpointHash, 0, newPoolNex, newPoolTokens)

                    // Record trade
                    try {
                        val trade = Trade(
                            poolId = pool.poolId,
                            direction = direction,
                            amountIn = amountIn,
                            amountOut = amountOut,
                            price = price,
                            nexReserveAfter = newPoolNex,
                            tokenReserveAfter = newPoolTokens,
                            txId = txId,
                            status = TradeStatus.CONFIRMED,
                        )
                        tradeRepo.insert(trade)
                        eventBus.emitTrade(trade)
                    } catch (e: Exception) {
                        logger.warn("Failed to record trade for txId={}: {}", txId, e.message)
                    }

                    DexResult.success(SwapResult(
                        txId = txId,
                        poolId = pool.poolId,
                        direction = direction,
                        amountIn = amountIn,
                        amountOut = amountOut,
                        price = price,
                    ))
                }
                is SdkResult.Failure -> {
                    logger.error("Swap failed: pool={}, error={}", pool.poolId, swapResult.error)
                    DexResult.failure(DexError.BroadcastFailed("Swap failed: ${swapResult.error}"))
                }
            }
        } finally {
            try { NexaSDK.wallet.destroy(wallet) } catch (e: Exception) { logger.warn("Wallet destroy failed: {}", e.message) }
        }
    }

    /**
     * Compute Nexa outpoint hash: SHA256(txIdem_LE_bytes || vout_uint32_LE).
     * Returns hex string (big-endian, matching Rostrum's outpoint_hash format).
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
        // Reverse to big-endian display order — matches Rostrum's outpoint_hash convention
        // (same as Bitcoin's reversed-hex display for txid/blockhash).
        // The SDK later reverses back to LE wire format when building transactions.
        return hash.reversedArray().joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
