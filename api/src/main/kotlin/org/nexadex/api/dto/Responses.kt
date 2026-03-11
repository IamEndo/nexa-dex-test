package org.nexadex.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: ApiError? = null,
) {
    companion object {
        fun <T> success(data: T) = ApiResponse(ok = true, data = data)
        fun <T> error(type: String, message: String, retryable: Boolean = false) =
            ApiResponse<T>(ok = false, error = ApiError(type, message, retryable))
    }
}

@Serializable
data class ApiError(
    val type: String,
    val message: String,
    val retryable: Boolean = false,
)

@Serializable
data class PoolResponse(
    val poolId: Int,
    val tokenGroupIdHex: String,
    val tokenTicker: String? = null,
    val tokenDecimals: Int = 0,
    val contractAddress: String,
    val contractVersion: String = "v2",
    val status: String,
    val nexReserve: Long,
    val tokenReserve: Long,
    val spotPrice: Double,
    val tvlNexSats: Long,
    val deployTxId: String? = null,
)

@Serializable
data class TradeResponse(
    val tradeId: Int,
    val poolId: Int,
    val direction: String,
    val amountIn: Long,
    val amountOut: Long,
    val price: Double,
    val txId: String?,
    val status: String,
    val createdAt: Long,
)

@Serializable
data class StatsResponse(
    val poolId: Int,
    val nexReserve: Long,
    val tokenReserve: Long,
    val spotPrice: Double,
    val tvlNexSats: Long,
    val volume24hNex: Long,
    val volume24hToken: Long,
    val tradeCount24h: Int,
    val priceChange24hPct: Double,
    val apyEstimatePct: Double,
)

@Serializable
data class TokenResponse(
    val groupIdHex: String,
    val name: String?,
    val ticker: String?,
    val decimals: Int,
    val documentUrl: String?,
)

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val uptime: Long,
    val pools: Int,
    val activePools: Int = pools,
    val connected: Boolean,
    val dbConnected: Boolean = true,
    val memoryUsedMb: Long = 0,
    val memoryMaxMb: Long = 0,
)

@Serializable
data class CandleResponse(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volumeNex: Long,
    val volumeToken: Long,
    val tradeCount: Int,
)
