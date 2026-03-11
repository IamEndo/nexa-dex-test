package org.nexadex.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PoolState(
    val poolId: Int,
    val tokenGroupIdHex: String,
    val lpGroupIdHex: String,
    val contractAddress: String,
    val contractVersion: String,
    val status: String,
    val nexReserve: Long,
    val tokenReserve: Long,
    val spotPrice: Double,
    val poolUtxoTxId: String?,
    val poolUtxoVout: Int?,
    val initialLpSupply: Long,
    val lpReserveBalance: Long,
    val lpInCirculation: Long,
    val lastUpdated: Long,
)

@Serializable
data class SwapResult(
    val txId: String,
    val poolId: Int,
    val direction: TradeDirection,
    val amountIn: Long,
    val amountOut: Long,
    val price: Double,
)

@Serializable
data class SwapBuildParams(
    val poolId: Int,
    val direction: TradeDirection,
    val amountIn: Long,
    val amountOut: Long,
    val price: Double,
    val priceImpactBps: Int,
    val minimumReceived: Long,
    val newPoolNex: Long,
    val newPoolTokens: Long,
    val poolUtxoTxId: String,
    val poolUtxoVout: Int,
    val estimatedFee: Long,
)

@Serializable
data class SwapTdppResult(
    val partialTxHex: String,
    val poolId: Int,
    val direction: TradeDirection,
    val amountIn: Long,
    val amountOut: Long,
    val price: Double,
    val priceImpactBps: Int,
    val minimumReceived: Long,
    val newPoolNex: Long,
    val newPoolTokens: Long,
    val totalInputSatoshis: Long = 0L,
)

@Serializable
data class LiquidityResult(
    val txId: String,
    val poolId: Int,
    val action: String,
    val nexAmount: Long = 0,
    val tokenAmount: Long = 0,
    val lpTokenAmount: Long = 0,
)
