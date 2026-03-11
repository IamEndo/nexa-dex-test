package org.nexadex.service

import org.nexadex.core.error.DexError
import org.nexadex.core.error.DexResult
import org.nexadex.core.model.*
import org.nexadex.data.repository.PoolRepository
import org.nexa.sdk.NexaSDK
import org.nexa.sdk.types.common.SdkResult
import org.nexa.sdk.types.contract.AmmDexPoolState
import org.nexa.sdk.types.primitives.NexaAmount
import org.nexa.sdk.types.wallet.Mnemonic
import org.nexa.sdk.types.wallet.Network
import org.slf4j.LoggerFactory

class LiquidityService(
    private val poolRepo: PoolRepository,
    private val poolService: PoolService,
) {
    private val logger = LoggerFactory.getLogger(LiquidityService::class.java)

    /**
     * Query on-chain LP state for a pool.
     */
    suspend fun getPoolLpState(pool: Pool): DexResult<AmmDexPoolState> {
        val instance = try {
            poolService.getContractInstance(pool)
        } catch (e: Exception) {
            return DexResult.failure(DexError.InternalError("Failed to get contract instance: ${e.message}"))
        }

        return when (val r = NexaSDK.contract.getAmmDexPoolState(
            instance = instance,
            tradeGroupId = pool.tokenGroupIdHex,
            lpGroupId = pool.lpGroupIdHex,
            initialLpSupply = pool.initialLpSupply,
        )) {
            is SdkResult.Success -> DexResult.success(r.value)
            is SdkResult.Failure -> DexResult.failure(DexError.InternalError("Failed to query pool state: ${r.error}"))
        }
    }

    /**
     * Compute expected LP tokens for an addLiquidity operation (preview/quote).
     */
    fun computeAddLiquidityQuote(
        nexSats: Long,
        tokenAmount: Long,
        poolNexReserve: Long,
        poolTokenReserve: Long,
        lpInCirculation: Long,
    ): Long {
        return if (lpInCirculation == 0L) {
            Math.sqrt(nexSats.toDouble() * tokenAmount.toDouble()).toLong()
        } else {
            val lpByNex = nexSats * lpInCirculation / poolNexReserve
            val lpByToken = tokenAmount * lpInCirculation / poolTokenReserve
            minOf(lpByNex, lpByToken)
        }
    }

    /**
     * Compute expected NEX/token withdrawal for a removeLiquidity operation (preview/quote).
     */
    fun computeRemoveLiquidityQuote(
        lpTokenAmount: Long,
        poolNexReserve: Long,
        poolTokenReserve: Long,
        lpInCirculation: Long,
    ): Pair<Long, Long> {
        if (lpInCirculation <= 0) return Pair(0L, 0L)
        val nexOut = lpTokenAmount * poolNexReserve / lpInCirculation
        val tokensOut = lpTokenAmount * poolTokenReserve / lpInCirculation
        return Pair(nexOut, tokensOut)
    }

    /**
     * Add liquidity to a pool. Anyone can call this (permissionless).
     * SDK handles UTXO selection, tx building, signing, and broadcasting.
     */
    suspend fun addLiquidity(
        poolId: Int,
        nexSats: Long,
        tokenAmount: Long,
        userMnemonic: String,
    ): DexResult<LiquidityResult> {
        val pool = poolRepo.findById(poolId)
            ?: return DexResult.failure(DexError.PoolNotFound(poolId))

        if (pool.status != PoolStatus.ACTIVE) {
            return DexResult.failure(DexError.PoolNotActive(poolId, pool.status.name))
        }
        if (nexSats <= 0 || tokenAmount <= 0) {
            return DexResult.failure(DexError.InvalidOperation("Both nexSats and tokenAmount must be positive"))
        }

        val mnemonic = when (val r = Mnemonic.parse(userMnemonic)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> return DexResult.failure(DexError.InvalidOperation("Invalid mnemonic"))
        }
        val wallet = when (val r = NexaSDK.wallet.createFromMnemonic(mnemonic, Network.MAINNET)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> return DexResult.failure(DexError.InternalError("Failed to create wallet: ${r.error}"))
        }

        return try {
            val instance = try {
                poolService.getContractInstance(pool)
            } catch (e: Exception) {
                return DexResult.failure(DexError.InternalError("Failed to get contract instance: ${e.message}"))
            }

            val nexAmount = when (val r = NexaAmount.fromSatoshis(nexSats)) {
                is SdkResult.Success -> r.value
                is SdkResult.Failure -> return DexResult.failure(DexError.InternalError("Invalid NEX amount"))
            }

            // Query on-chain LP state to compute LP tokens minted
            val lpState = when (val r = NexaSDK.contract.getAmmDexPoolState(
                instance = instance,
                tradeGroupId = pool.tokenGroupIdHex,
                lpGroupId = pool.lpGroupIdHex,
                initialLpSupply = pool.initialLpSupply,
            )) {
                is SdkResult.Success -> r.value
                is SdkResult.Failure -> {
                    logger.warn("Could not query LP state for preview: {}", r.error)
                    null
                }
            }

            val expectedLpTokens = if (lpState != null) {
                computeAddLiquidityQuote(
                    nexSats, tokenAmount,
                    lpState.nexReserve.satoshis, lpState.tokenReserve,
                    lpState.lpInCirculation,
                )
            } else 0L

            logger.info(
                "Adding liquidity: pool={}, nex={}, tokens={}, expectedLpTokens={}, onChainNex={}, onChainTokens={}, lpInCirc={}, lpReserve={}",
                poolId, nexSats, tokenAmount, expectedLpTokens,
                lpState?.nexReserve?.satoshis, lpState?.tokenReserve,
                lpState?.lpInCirculation, lpState?.lpReserveBalance,
            )

            // Check if LP reserve has room (contract enforces newLpReserve >= 1000)
            if (lpState != null && expectedLpTokens > 0) {
                val newReserve = lpState.lpReserveBalance - expectedLpTokens
                if (newReserve < 1000L) {
                    return DexResult.failure(DexError.InvalidOperation(
                        "Cannot add liquidity: LP reserve too low (${lpState.lpReserveBalance} tokens, need to keep >= 1000). " +
                        "This pool was deployed with insufficient LP reserve. A new pool with proper LP allocation is needed."
                    ))
                }
            }

            val result = NexaSDK.contract.addAmmDexLiquidity(
                wallet = wallet,
                instance = instance,
                nexAmount = nexAmount,
                tokenAmount = tokenAmount,
                tradeGroupId = pool.tokenGroupIdHex,
                lpGroupId = pool.lpGroupIdHex,
                initialLpSupply = pool.initialLpSupply,
            )

            when (result) {
                is SdkResult.Success -> {
                    val txId = result.value.txIdem.hex
                    logger.info("Add liquidity succeeded: pool={}, txId={}, lpTokens={}", poolId, txId, expectedLpTokens)

                    // Update reserves and UTXO (pool UTXO is output 0)
                    val newNex = pool.nexReserve + nexSats
                    val newTokens = pool.tokenReserve + tokenAmount
                    poolRepo.updatePoolUtxoAndReserves(poolId, txId, 0, newNex, newTokens)

                    DexResult.success(LiquidityResult(
                        txId = txId,
                        poolId = poolId,
                        action = "ADD",
                        nexAmount = nexSats,
                        tokenAmount = tokenAmount,
                        lpTokenAmount = expectedLpTokens,
                    ))
                }
                is SdkResult.Failure -> {
                    logger.error("Add liquidity failed: pool={}, error={}", poolId, result.error)
                    DexResult.failure(DexError.BroadcastFailed("Add liquidity failed: ${result.error}"))
                }
            }
        } finally {
            try { NexaSDK.wallet.destroy(wallet) } catch (e: Exception) { logger.warn("Wallet destroy failed: {}", e.message) }
        }
    }

    /**
     * Remove liquidity from a pool by returning LP tokens.
     * Computes proportional NEX/token withdrawal and updates reserves.
     */
    suspend fun removeLiquidity(
        poolId: Int,
        lpTokenAmount: Long,
        userMnemonic: String,
    ): DexResult<LiquidityResult> {
        val pool = poolRepo.findById(poolId)
            ?: return DexResult.failure(DexError.PoolNotFound(poolId))

        if (pool.status != PoolStatus.ACTIVE) {
            return DexResult.failure(DexError.PoolNotActive(poolId, pool.status.name))
        }
        if (lpTokenAmount <= 0) {
            return DexResult.failure(DexError.InvalidOperation("lpTokenAmount must be positive"))
        }

        val mnemonic = when (val r = Mnemonic.parse(userMnemonic)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> return DexResult.failure(DexError.InvalidOperation("Invalid mnemonic"))
        }
        val wallet = when (val r = NexaSDK.wallet.createFromMnemonic(mnemonic, Network.MAINNET)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> return DexResult.failure(DexError.InternalError("Failed to create wallet: ${r.error}"))
        }

        return try {
            val instance = try {
                poolService.getContractInstance(pool)
            } catch (e: Exception) {
                return DexResult.failure(DexError.InternalError("Failed to get contract instance: ${e.message}"))
            }

            // Query on-chain LP state to compute expected withdrawal amounts
            val lpState = when (val r = NexaSDK.contract.getAmmDexPoolState(
                instance = instance,
                tradeGroupId = pool.tokenGroupIdHex,
                lpGroupId = pool.lpGroupIdHex,
                initialLpSupply = pool.initialLpSupply,
            )) {
                is SdkResult.Success -> r.value
                is SdkResult.Failure -> return DexResult.failure(DexError.InternalError("Failed to query pool LP state: ${r.error}"))
            }

            if (lpState.lpInCirculation <= 0) {
                return DexResult.failure(DexError.InvalidOperation("No LP tokens in circulation"))
            }

            val (expectedNexOut, expectedTokensOut) = computeRemoveLiquidityQuote(
                lpTokenAmount,
                lpState.nexReserve.satoshis, lpState.tokenReserve,
                lpState.lpInCirculation,
            )

            logger.info(
                "Removing liquidity: pool={}, lpTokens={}, expectedNex={}, expectedTokens={}",
                poolId, lpTokenAmount, expectedNexOut, expectedTokensOut,
            )

            val result = NexaSDK.contract.removeAmmDexLiquidity(
                wallet = wallet,
                instance = instance,
                lpTokenAmount = lpTokenAmount,
                tradeGroupId = pool.tokenGroupIdHex,
                lpGroupId = pool.lpGroupIdHex,
                initialLpSupply = pool.initialLpSupply,
            )

            when (result) {
                is SdkResult.Success -> {
                    val txId = result.value.txIdem.hex
                    logger.info(
                        "Remove liquidity succeeded: pool={}, txId={}, nexOut={}, tokensOut={}",
                        poolId, txId, expectedNexOut, expectedTokensOut,
                    )

                    // Update reserves and UTXO outpoint
                    val newNex = lpState.nexReserve.satoshis - expectedNexOut
                    val newTokens = lpState.tokenReserve - expectedTokensOut
                    poolRepo.updatePoolUtxoAndReserves(poolId, txId, 0, newNex, newTokens)

                    DexResult.success(LiquidityResult(
                        txId = txId,
                        poolId = poolId,
                        action = "REMOVE",
                        nexAmount = expectedNexOut,
                        tokenAmount = expectedTokensOut,
                        lpTokenAmount = lpTokenAmount,
                    ))
                }
                is SdkResult.Failure -> {
                    logger.error("Remove liquidity failed: pool={}, error={}", poolId, result.error)
                    DexResult.failure(DexError.BroadcastFailed("Remove liquidity failed: ${result.error}"))
                }
            }
        } finally {
            try { NexaSDK.wallet.destroy(wallet) } catch (e: Exception) { logger.warn("Wallet destroy failed: {}", e.message) }
        }
    }
}
