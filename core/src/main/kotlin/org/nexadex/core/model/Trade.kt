package org.nexadex.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TradeDirection {
    BUY,  // NEX → Token
    SELL, // Token → NEX
}

@Serializable
enum class TradeStatus {
    PENDING,
    CONFIRMED,
    FAILED,
}

@Serializable
data class Trade(
    val tradeId: Int = 0,
    val poolId: Int,
    val direction: TradeDirection,
    val amountIn: Long,
    val amountOut: Long,
    val price: Double = 0.0,
    val nexReserveAfter: Long,
    val tokenReserveAfter: Long,
    val txId: String? = null,
    val traderAddress: String? = null,
    val status: TradeStatus = TradeStatus.PENDING,
    val createdAt: Long = 0L,
)
