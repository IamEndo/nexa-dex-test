package org.nexadex.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class PoolStateResponse(
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
    val templateHashHex: String,
    val constraintHashHex: String,
    val lastUpdated: Long,
)

@Serializable
data class SwapBuildRequest(
    val poolId: Int,
    val direction: String,
    val amountIn: Long,
    val destinationAddress: String,
    val maxSlippageBps: Int = 100,
)

@Serializable
data class SwapBuildResponse(
    val poolId: Int,
    val direction: String,
    val amountIn: Long,
    val amountOut: Long,
    val price: Double,
    val priceImpactBps: Int,
    val minimumReceived: Long,
    val poolOutputScriptHex: String,
    val poolOutputNexAmount: Long,
    val poolOutputTokenAmount: Long,
    val userOutputAmount: Long,
    val estimatedFee: Long,
    val poolUtxoTxId: String?,
    val poolUtxoVout: Int?,
)

@Serializable
data class SwapBroadcastRequest(
    val signedTxHex: String,
    val poolId: Int,
)

@Serializable
data class SwapBroadcastResponse(
    val txId: String,
    val poolId: Int,
    val status: String,
)

@Serializable
data class SwapStatusResponse(
    val txId: String,
    val status: String,
    val confirmations: Int? = null,
    val poolId: Int? = null,
)

@Serializable
data class SwapExecuteRequest(
    val poolId: Int,
    val direction: String,
    val amountIn: Long,
    val mnemonic: String,
    val maxSlippageBps: Int = 100,
)

@Serializable
data class SwapExecuteResponse(
    val txId: String,
    val poolId: Int,
    val direction: String,
    val amountIn: Long,
    val amountOut: Long,
    val price: Double,
    val status: String,
)

@Serializable
data class LiquidityResponse(
    val txId: String,
    val poolId: Int,
    val action: String,
    val nexAmount: Long = 0,
    val tokenAmount: Long = 0,
    val lpTokenAmount: Long = 0,
    val status: String,
)

@Serializable
data class LiquidityQuoteResponse(
    val poolId: Int,
    val action: String,
    val nexAmount: Long = 0,
    val tokenAmount: Long = 0,
    val lpTokenAmount: Long = 0,
    val lpInCirculation: Long = 0,
    val poolNexReserve: Long = 0,
    val poolTokenReserve: Long = 0,
)
