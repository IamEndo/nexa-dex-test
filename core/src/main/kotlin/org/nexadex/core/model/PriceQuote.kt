package org.nexadex.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PriceQuote(
    val poolId: Int,
    val direction: TradeDirection,
    val amountIn: Long,
    val amountOut: Long,
    val price: Double,
    val priceImpactBps: Int,
    val minimumReceived: Long,
    val nexReserveBefore: Long,
    val tokenReserveBefore: Long,
)
