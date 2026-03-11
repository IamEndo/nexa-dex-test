package org.nexadex.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PoolStats(
    val poolId: Int,
    val tokenGroupIdHex: String,
    val tokenTicker: String? = null,
    val nexReserve: Long,
    val tokenReserve: Long,
    val spotPrice: Double,
    val tvlNexSats: Long,
    val volume24hNex: Long = 0L,
    val volume24hToken: Long = 0L,
    val tradeCount24h: Int = 0,
    val priceChange24hPct: Double = 0.0,
    val apyEstimatePct: Double = 0.0,
)
