package org.nexadex.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class CandleInterval(val label: String, val durationMs: Long) {
    M1("1m", 60_000L),
    M5("5m", 300_000L),
    M15("15m", 900_000L),
    H1("1h", 3_600_000L),
    H4("4h", 14_400_000L),
    D1("1d", 86_400_000L);

    companion object {
        fun fromLabel(label: String): CandleInterval? =
            entries.firstOrNull { it.label == label }
    }
}

@Serializable
data class OhlcvCandle(
    val poolId: Int,
    val interval: CandleInterval,
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volumeNex: Long = 0L,
    val volumeToken: Long = 0L,
    val tradeCount: Int = 0,
)
