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
import org.nexa.sdk.types.primitives.Address
import org.nexa.sdk.types.wallet.AddressType
import org.nexa.sdk.types.wallet.Mnemonic
import org.nexa.sdk.types.wallet.Network
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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

    // Track recent broadcast timestamps per pool to avoid stale chain refresh.
    // After broadcast, Rostrum may take several seconds to index the new tx.
    // During that window, getAmmDexPoolState returns the OLD outpoint, which
    // would overwrite our correct post-broadcast outpoint in DB.
    private val recentBroadcasts = ConcurrentHashMap<Int, Instant>()

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
                    val errMsg = result.error.toString()
                    // If tx is already in mempool (Wally broadcast with no NOPOST flag),
                    // that's fine — decode to get txIdem and treat as success.
                    // NOTE: txn-txpool-conflict is the Nexa node error (error 258) when
                    // re-broadcasting a tx that's already in the mempool. Different from
                    // txn-mempool-conflict (Bitcoin-style). Both must be handled.
                    if (errMsg.contains("txn-mempool-conflict") || errMsg.contains("already in") ||
                        errMsg.contains("txn-already-in-mempool") || errMsg.contains("Already in") ||
                        errMsg.contains("txn-txpool-conflict")
                    ) {
                        logger.info("Tx already in mempool for pool $poolId (Wally broadcast), extracting txIdem")
                        val txIdem = extractTxIdemFromSignedTx(signedTxHex)
                        if (txIdem != null) {
                            return DexResult.success(txIdem)
                        }
                    }
                    logger.warn("Relay failed for pool $poolId: {}", result.error)
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
     * Extract txIdem from a signed transaction hex by decoding it.
     * Used when the tx is already in mempool (Wally broadcast it) and we just need the ID.
     */
    private fun extractTxIdemFromSignedTx(signedTxHex: String): String? {
        return try {
            when (val decoded = NexaSDK.transaction.decode(signedTxHex)) {
                is SdkResult.Success -> decoded.value.txIdem.hex
                is SdkResult.Failure -> {
                    logger.warn("Could not decode tx for txIdem extraction: {}", decoded.error)
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn("txIdem extraction failed: {}", e.message)
            null
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
                    logger.warn("  rawTxHex[0..200]={}", signedTxHex.take(200))
                    tx.inputs.forEachIndexed { idx, input ->
                        val sigBytes = try { input.scriptSig.bytes } catch (_: Exception) { ByteArray(0) }
                        val sigHex = sigBytes.joinToString("") { "%02x".format(it) }
                        logger.warn("  Input[{}]: outpointHash={}, amount={} sat, type={}, scriptSigLen={}, scriptSig[0..40]={}",
                            idx,
                            input.outpointHash ?: "N/A",
                            input.amount.satoshis,
                            input.inputType,
                            sigBytes.size,
                            sigHex.take(40),
                        )
                    }
                    tx.outputs.forEachIndexed { idx, output ->
                        val scriptBytes = try { output.script.bytes } catch (_: Exception) { ByteArray(0) }
                        val scriptHex = scriptBytes.joinToString("") { "%02x".format(it) }
                        logger.warn("  Output[{}]: amount={} sat, type={}, groupId={}, groupAmt={}, scriptLen={}, script[0..60]={}",
                            idx, output.amount.satoshis, output.outputType,
                            output.groupId?.hashHex?.take(16) ?: "none",
                            output.groupAmount ?: 0,
                            scriptBytes.size,
                            scriptHex.take(60),
                        )
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
        walletTokenUtxos: List<WalletAssetUtxo> = emptyList(),
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

        // Refresh pool state from chain to avoid stale outpoints (txn-txpool-conflict).
        // The indexer polls every 15s, but swaps can happen faster.
        // IMPORTANT: Skip chain refresh if we recently broadcast for this pool,
        // because Rostrum may not have indexed the new tx yet and would return
        // the OLD outpoint, overwriting our correct post-broadcast outpoint in DB.
        var poolOutpoint = pool.poolUtxoTxId!!
        var nexReserve = pool.nexReserve
        var tokenReserve = pool.tokenReserve
        val lastBroadcast = recentBroadcasts[poolId]
        val skipChainRefresh = lastBroadcast != null &&
            Duration.between(lastBroadcast, Instant.now()).seconds < 15
        if (skipChainRefresh) {
            logger.info("prepareSwapTdpp: skipping chain refresh (broadcast {}s ago), trusting DB outpoint={}",
                Duration.between(lastBroadcast, Instant.now()).seconds, poolOutpoint.take(12))
        } else {
            try {
                when (val r = NexaSDK.contract.getAmmDexPoolState(
                    instance = instance,
                    tradeGroupId = pool.tokenGroupIdHex,
                    lpGroupId = pool.lpGroupIdHex ?: "",
                    initialLpSupply = pool.initialLpSupply,
                )) {
                    is SdkResult.Success -> {
                        val state = r.value
                        if (state.poolOutpointHash != null) {
                            if (state.poolOutpointHash != poolOutpoint) {
                                logger.info("prepareSwapTdpp: refreshed pool state from chain: outpoint {} -> {}, nex {} -> {}, tokens {} -> {}",
                                    poolOutpoint.take(12), state.poolOutpointHash!!.take(12),
                                    nexReserve, state.nexReserve.satoshis, tokenReserve, state.tokenReserve)
                                poolOutpoint = state.poolOutpointHash!!
                                nexReserve = state.nexReserve.satoshis
                                tokenReserve = state.tokenReserve
                                // Update DB for future requests
                                poolRepo.updatePoolUtxoAndReserves(poolId, poolOutpoint, 0, nexReserve, tokenReserve)
                            }
                        }
                    }
                    is SdkResult.Failure -> {
                        logger.warn("prepareSwapTdpp: could not refresh pool state, using DB values: {}", r.error)
                    }
                }
            } catch (e: Exception) {
                logger.warn("prepareSwapTdpp: pool state refresh failed, using DB values: {}", e.message)
            }
        }

        val sdkDirection = org.nexa.sdk.types.contract.TradeDirection.valueOf(direction.name)

        logger.info("prepareSwapTdpp: pool={}, dir={}, outpoint={}, nexReserve={}, tokenReserve={}",
            poolId, direction, poolOutpoint.take(12), nexReserve, tokenReserve)

        // BUY: first SDK overload (pool-side only). Wally adds NEX funding + signs.
        // SELL: new overload with pre-resolved wallet token UTXOs (from /assets TDPP).
        //   Uses Wally's own outpoint hashes so getTxo() can find and enrich them.
        //   No FUND_GROUPS needed — token inputs are pre-included.
        val partialResult = if (direction == TradeDirection.SELL) {
            // Filter wallet UTXOs by trade group and build SDK input list
            val matchingUtxos = walletTokenUtxos
                .filter { it.groupIdHex?.equals(pool.tokenGroupIdHex, ignoreCase = true) == true }
                .filter { it.tokenAmount > 0 }

            logger.info("prepareSwapTdpp SELL: {} wallet UTXOs total, {} match trade group {}",
                walletTokenUtxos.size, matchingUtxos.size, pool.tokenGroupIdHex.take(16))
            matchingUtxos.forEachIndexed { idx, u ->
                logger.info("  UTXO[{}]: outpoint={}, tokens={}, nex={}",
                    idx, u.outpointHash.take(16), u.tokenAmount, u.amount)
            }

            if (matchingUtxos.isEmpty()) {
                return DexResult.failure(DexError.InvalidOperation(
                    "SELL: no matching token UTXOs in wallet. Reconnect wallet or wait for /assets to load.",
                ))
            }

            val sdkUtxos = matchingUtxos.map { Triple(it.outpointHash, it.amount, it.tokenAmount) }
            NexaSDK.contract.buildAmmDexSwapPartial(
                instance = instance,
                direction = sdkDirection,
                amountIn = amountIn,
                tradeGroupId = pool.tokenGroupIdHex,
                knownPoolOutpointHash = poolOutpoint,
                knownNexReserve = nexReserve,
                knownTokenReserve = tokenReserve,
                walletTokenUtxos = sdkUtxos,
            )
        } else {
            // BUY: pool-side only
            NexaSDK.contract.buildAmmDexSwapPartial(
                instance = instance,
                direction = sdkDirection,
                amountIn = amountIn,
                tradeGroupId = pool.tokenGroupIdHex,
                knownPoolOutpointHash = poolOutpoint,
                knownNexReserve = nexReserve,
                knownTokenReserve = tokenReserve,
            )
        }

        return when (partialResult) {
            is SdkResult.Success -> {
                val r = partialResult.value
                val price = if (r.amountIn > 0) r.expectedAmountOut.toDouble() / r.amountIn.toDouble() else 0.0

                // Compute price impact using our AMM math
                val priceImpactBps = when (direction) {
                    TradeDirection.SELL -> AmmMath.computePriceImpactBps(amountIn, r.expectedAmountOut, tokenReserve, nexReserve) ?: 0
                    TradeDirection.BUY -> AmmMath.computePriceImpactBps(amountIn, r.expectedAmountOut, nexReserve, tokenReserve) ?: 0
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

        // Track broadcast time so prepareSwapTdpp skips chain refresh during
        // the window where Rostrum hasn't indexed our new tx yet.
        recentBroadcasts[poolId] = Instant.now()

        // For BUY swaps, extract the token delivery address from the signed tx.
        // Output[1] is the token delivery — Wally replaced OP_TMPL with the user's
        // actual address script. We capture this address so subsequent SELL swaps
        // can find the user's tokens (HD wallets use many addresses).
        var tokenDeliveryAddress: String? = null
        if (direction == TradeDirection.BUY) {
            try {
                when (val decoded = NexaSDK.transaction.decode(signedTxHex)) {
                    is SdkResult.Success -> {
                        val tx = decoded.value
                        if (tx.outputs.size > 1) {
                            // Extract user address from output[1] script.
                            // After Wally replaces OP_TMPL, script contains:
                            //   [group_data...] 00 51 14 <20-byte-constraintArgsHash>
                            // The 00 51 14 pattern is P2ST: OP_0 OP_1 PUSH20
                            val scriptBytes = tx.outputs[1].script.bytes
                            tokenDeliveryAddress = extractP2STAddress(scriptBytes)
                            if (tokenDeliveryAddress != null) {
                                logger.info("BUY token delivery address captured: {}", tokenDeliveryAddress!!.take(20) + "...")
                            } else {
                                logger.warn("BUY output[1]: could not extract address from script (len={})", scriptBytes.size)
                            }
                        }
                    }
                    is SdkResult.Failure -> {
                        logger.warn("Could not decode signed tx for address capture: {}", decoded.error)
                    }
                }
            } catch (e: Exception) {
                logger.warn("Token delivery address capture failed: {}", e.message)
            }
        }

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
                tokenDeliveryAddress = tokenDeliveryAddress,
            ),
        )
    }

    /**
     * Build a partial liquidity transaction for TDPP (Wally wallet signing).
     * Returns the partial tx hex and metadata for the pending TDPP.
     */
    suspend fun prepareLiquidityTdpp(
        poolId: Int,
        action: String, // "add_liquidity" or "remove_liquidity"
        nexSats: Long = 0,
        tokenAmount: Long = 0,
        lpTokenAmount: Long = 0,
        walletAssets: List<WalletAssetUtxo> = emptyList(),
    ): DexResult<LiquidityTdppResult> {
        val pool = poolRepo.findById(poolId)
            ?: return DexResult.failure(DexError.PoolNotFound(poolId))

        if (pool.status != PoolStatus.ACTIVE) {
            return DexResult.failure(DexError.PoolNotActive(poolId, pool.status.name))
        }

        val instance = try {
            poolService.getContractInstance(pool)
        } catch (e: Exception) {
            return DexResult.failure(DexError.InternalError("Failed to get contract instance: ${e.message}"))
        }

        // Query on-chain state — need BOTH pool and LP reserve outpoints
        val lpState: AmmDexPoolState
        val lastBroadcast = recentBroadcasts[poolId]
        val skipChainRefresh = lastBroadcast != null &&
            Duration.between(lastBroadcast, Instant.now()).seconds < 15

        lpState = try {
            when (val r = NexaSDK.contract.getAmmDexPoolState(
                instance = instance,
                tradeGroupId = pool.tokenGroupIdHex,
                lpGroupId = pool.lpGroupIdHex,
                initialLpSupply = pool.initialLpSupply,
            )) {
                is SdkResult.Success -> {
                    val state = r.value
                    // Update DB if outpoint changed and not in recent-broadcast window
                    if (!skipChainRefresh && state.poolOutpointHash != null) {
                        val dbOutpoint = pool.poolUtxoTxId
                        if (state.poolOutpointHash != dbOutpoint) {
                            logger.info("prepareLiquidityTdpp: refreshed pool state: outpoint {} -> {}",
                                dbOutpoint?.take(12), state.poolOutpointHash!!.take(12))
                            poolRepo.updatePoolUtxoAndReserves(
                                poolId, state.poolOutpointHash!!, 0,
                                state.nexReserve.satoshis, state.tokenReserve,
                            )
                        }
                    }
                    state
                }
                is SdkResult.Failure -> {
                    return DexResult.failure(DexError.InternalError("Failed to query pool LP state: ${r.error}"))
                }
            }
        } catch (e: Exception) {
            return DexResult.failure(DexError.InternalError("Pool state query failed: ${e.message}"))
        }

        val poolOutpoint = lpState.poolOutpointHash
            ?: return DexResult.failure(DexError.InternalError("Pool outpoint not available from chain"))
        val lpReserveOutpoint = lpState.lpReserveOutpointHash
            ?: return DexResult.failure(DexError.InternalError("LP reserve outpoint not available from chain"))

        logger.info("prepareLiquidityTdpp: action={}, pool={}, poolOutpoint={}, lpOutpoint={}, nex={}, tokens={}, lpReserve={}, lpCirc={}",
            action, poolId, poolOutpoint.take(12), lpReserveOutpoint.take(12),
            lpState.nexReserve.satoshis, lpState.tokenReserve,
            lpState.lpReserveBalance, lpState.lpInCirculation)

        val partialResult = when (action) {
            "add_liquidity" -> {
                // Filter wallet UTXOs for trade tokens
                val matchingUtxos = walletAssets
                    .filter { it.groupIdHex?.equals(pool.tokenGroupIdHex, ignoreCase = true) == true }
                    .filter { it.tokenAmount > 0 }

                logger.info("prepareLiquidityTdpp ADD: {} wallet UTXOs match trade group", matchingUtxos.size)

                if (matchingUtxos.isEmpty()) {
                    return DexResult.failure(DexError.InvalidOperation(
                        "No matching trade token UTXOs in wallet. Reconnect wallet or wait for /assets to load.",
                    ))
                }

                val sdkUtxos = matchingUtxos.map { Triple(it.outpointHash, it.amount, it.tokenAmount) }
                NexaSDK.contract.buildAmmDexAddLiquidityPartial(
                    instance = instance,
                    nexAmount = nexSats,
                    tokenAmount = tokenAmount,
                    tradeGroupId = pool.tokenGroupIdHex,
                    lpGroupId = pool.lpGroupIdHex,
                    initialLpSupply = pool.initialLpSupply,
                    knownPoolOutpointHash = poolOutpoint,
                    knownLpReserveOutpointHash = lpReserveOutpoint,
                    knownNexReserve = lpState.nexReserve.satoshis,
                    knownTokenReserve = lpState.tokenReserve,
                    knownLpReserveBalance = lpState.lpReserveBalance,
                    knownLpInCirculation = lpState.lpInCirculation,
                    walletTokenUtxos = sdkUtxos,
                )
            }
            "remove_liquidity" -> {
                // Filter wallet UTXOs for LP tokens
                val matchingUtxos = walletAssets
                    .filter { it.groupIdHex?.equals(pool.lpGroupIdHex, ignoreCase = true) == true }
                    .filter { it.tokenAmount > 0 }

                logger.info("prepareLiquidityTdpp REMOVE: {} wallet UTXOs match LP group", matchingUtxos.size)

                if (matchingUtxos.isEmpty()) {
                    return DexResult.failure(DexError.InvalidOperation(
                        "No matching LP token UTXOs in wallet. Reconnect wallet or wait for /assets to load.",
                    ))
                }

                val sdkUtxos = matchingUtxos.map { Triple(it.outpointHash, it.amount, it.tokenAmount) }
                NexaSDK.contract.buildAmmDexRemoveLiquidityPartial(
                    instance = instance,
                    lpTokenAmount = lpTokenAmount,
                    tradeGroupId = pool.tokenGroupIdHex,
                    lpGroupId = pool.lpGroupIdHex,
                    initialLpSupply = pool.initialLpSupply,
                    knownPoolOutpointHash = poolOutpoint,
                    knownLpReserveOutpointHash = lpReserveOutpoint,
                    knownNexReserve = lpState.nexReserve.satoshis,
                    knownTokenReserve = lpState.tokenReserve,
                    knownLpReserveBalance = lpState.lpReserveBalance,
                    knownLpInCirculation = lpState.lpInCirculation,
                    walletLpUtxos = sdkUtxos,
                )
            }
            else -> return DexResult.failure(DexError.InvalidOperation("Unknown liquidity action: $action"))
        }

        return when (partialResult) {
            is SdkResult.Success -> {
                val r = partialResult.value
                logger.info("Liquidity TDPP partial tx built: pool={}, action={}, nex={}, tokens={}, lp={}, txLen={}",
                    poolId, action, r.nexAmount, r.tokenAmount, r.lpTokenAmount, r.partialTxHex.length)

                DexResult.success(
                    LiquidityTdppResult(
                        partialTxHex = r.partialTxHex,
                        poolId = poolId,
                        action = action,
                        nexAmount = r.nexAmount,
                        tokenAmount = r.tokenAmount,
                        lpTokenAmount = r.lpTokenAmount,
                        newPoolNex = r.newPoolNex,
                        newPoolTokens = r.newPoolTokens,
                        newLpReserve = r.newLpReserve,
                        totalInputSatoshis = r.totalInputSatoshis,
                    ),
                )
            }
            is SdkResult.Failure -> {
                logger.error("Liquidity TDPP partial tx build failed: pool={}, error={}", poolId, partialResult.error)
                DexResult.failure(DexError.InternalError("Failed to build liquidity partial tx: ${partialResult.error}"))
            }
        }
    }

    /**
     * Broadcast a Wally-signed liquidity transaction and update pool reserves.
     */
    suspend fun broadcastAndRecordLiquidity(
        signedTxHex: String,
        poolId: Int,
        action: String,
        nexAmount: Long,
        tokenAmount: Long,
        lpTokenAmount: Long,
        newNexReserve: Long,
        newTokenReserve: Long,
    ): DexResult<LiquidityResult> {
        val broadcastResult = broadcastTransaction(signedTxHex, poolId)
        if (broadcastResult.isFailure) {
            return DexResult.failure(broadcastResult.errorOrNull()!!)
        }

        val txIdem = broadcastResult.getOrNull()!!

        // Compute outpoint hash for pool UTXO tracking (output[0] is always the new pool UTXO)
        val outpointHash = computeOutpointHash(txIdem, 0)
        logger.info("Liquidity broadcast success: txIdem={}, outpointHash={}", txIdem, outpointHash.take(12))

        poolRepo.updatePoolUtxoAndReserves(poolId, outpointHash, 0, newNexReserve, newTokenReserve)
        recentBroadcasts[poolId] = Instant.now()

        val resultAction = if (action == "add_liquidity") "ADD" else "REMOVE"

        return DexResult.success(
            LiquidityResult(
                txId = txIdem,
                poolId = poolId,
                action = resultAction,
                nexAmount = nexAmount,
                tokenAmount = tokenAmount,
                lpTokenAmount = lpTokenAmount,
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
     * Extract a P2ST address from a raw output script.
     * Looks for the pattern: 00 51 14 <20-byte-hash> (OP_0 OP_1 PUSH20 hash).
     * Returns the CashAddr string or null if not found.
     */
    private fun extractP2STAddress(scriptBytes: ByteArray): String? {
        // Find P2ST marker: 00 51 14 (OP_0 OP_1 PUSH20)
        for (i in 0 until scriptBytes.size - 22) {
            if (scriptBytes[i] == 0x00.toByte() &&
                scriptBytes[i + 1] == 0x51.toByte() &&
                scriptBytes[i + 2] == 0x14.toByte()
            ) {
                val hash = scriptBytes.sliceArray(i + 3 until i + 23)
                return when (val addr = Address.fromComponents(
                    Network.MAINNET, AddressType.PAY_TO_SCRIPT_TEMPLATE, hash,
                )) {
                    is SdkResult.Success -> addr.value.toString()
                    is SdkResult.Failure -> null
                }
            }
        }
        return null
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

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /**
     * Strip OP_TMPL_SKIP (0xF0) outputs from a signed transaction.
     * NiftyArt-proven pattern: the OP_TMPL_SKIP output is a FUND_GROUPS hint
     * that tells Wally what tokens to add. After Wally signs, server removes it
     * before broadcasting. Safe because PARTIAL sighash doesn't cover it.
     *
     * Nexa tx wire format:
     *   [version:4][inputCount:varint][inputs...][outputCount:varint][outputs...][locktime:4]
     * Each output: [type:1][amount:8][scriptLen:varint][script:scriptLen]
     */
    fun stripTmplSkipOutputs(txHex: String): String {
        val bytes = hexToBytes(txHex)
        var pos = 4 // skip version (4 bytes)

        // Skip inputs
        val (inputCount, icBytes) = readVarInt(bytes, pos)
        pos += icBytes
        for (i in 0 until inputCount.toInt()) {
            pos += 1  // input type
            pos += 32 // outpoint hash
            pos += 8  // amount
            val (scriptLen, slBytes) = readVarInt(bytes, pos)
            pos += slBytes
            pos += scriptLen.toInt() // scriptSig
            pos += 4 // sequence
        }

        // Parse outputs
        val outputCountPos = pos
        val (outputCount, ocBytes) = readVarInt(bytes, pos)
        pos += ocBytes

        data class OutputRange(val start: Int, val end: Int, val isTmplSkip: Boolean)
        val outputs = mutableListOf<OutputRange>()
        for (i in 0 until outputCount.toInt()) {
            val start = pos
            pos += 1  // output type
            pos += 8  // amount
            val (scriptLen, slBytes) = readVarInt(bytes, pos)
            pos += slBytes
            val scriptEnd = pos + scriptLen.toInt()
            // OP_TMPL_SKIP = 0xF0 as last byte of script
            val isTmplSkip = scriptLen > 0 && bytes[scriptEnd - 1] == 0xF0.toByte()
            pos = scriptEnd
            outputs.add(OutputRange(start, pos, isTmplSkip))
        }

        val locktimePos = pos
        val keepOutputs = outputs.filter { !it.isTmplSkip }
        if (keepOutputs.size == outputs.size) return txHex // nothing to strip

        val stripped = outputs.count { it.isTmplSkip }
        logger.info("stripTmplSkipOutputs: removing {} OP_TMPL_SKIP output(s), {} -> {} outputs",
            stripped, outputs.size, keepOutputs.size)

        // Rebuild tx: version + inputs + new output count + kept outputs + locktime
        val result = java.io.ByteArrayOutputStream(bytes.size)
        result.write(bytes, 0, outputCountPos) // version + inputs
        result.write(writeVarInt(keepOutputs.size.toLong())) // new output count
        for (o in keepOutputs) {
            result.write(bytes, o.start, o.end - o.start)
        }
        result.write(bytes, locktimePos, bytes.size - locktimePos) // locktime

        return bytesToHex(result.toByteArray())
    }

    /** Read a Bitcoin-style variable-length integer. Returns (value, bytesConsumed). */
    private fun readVarInt(bytes: ByteArray, offset: Int): Pair<Long, Int> {
        val first = bytes[offset].toInt() and 0xFF
        return when {
            first < 0xFD -> Pair(first.toLong(), 1)
            first == 0xFD -> {
                val v = ((bytes[offset + 1].toInt() and 0xFF)) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8)
                Pair(v.toLong(), 3)
            }
            first == 0xFE -> {
                val v = ((bytes[offset + 1].toInt() and 0xFF)) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 3].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 4].toInt() and 0xFF) shl 24)
                Pair(v.toLong() and 0xFFFFFFFFL, 5)
            }
            else -> {
                var v = 0L
                for (i in 0 until 8) {
                    v = v or ((bytes[offset + 1 + i].toLong() and 0xFF) shl (i * 8))
                }
                Pair(v, 9)
            }
        }
    }

    /** Write a Bitcoin-style variable-length integer. */
    private fun writeVarInt(value: Long): ByteArray = when {
        value < 0xFD -> byteArrayOf(value.toByte())
        value <= 0xFFFF -> byteArrayOf(0xFD.toByte(), (value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())
        value <= 0xFFFFFFFFL -> byteArrayOf(
            0xFE.toByte(),
            (value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(), ((value ushr 24) and 0xFF).toByte(),
        )
        else -> byteArrayOf(0xFF.toByte()) + ByteArray(8) { i -> ((value ushr (i * 8)) and 0xFF).toByte() }
    }
}
