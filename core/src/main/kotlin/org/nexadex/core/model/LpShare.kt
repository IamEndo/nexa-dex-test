package org.nexadex.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LpShare(
    val shareId: Int = 0,
    val poolId: Int,
    val providerAddress: String,
    val nexContributed: Long,
    val tokensContributed: Long,
    val sharePct: Double = 0.0,
    val depositTxId: String? = null,
    val createdAt: Long = 0L,
)
