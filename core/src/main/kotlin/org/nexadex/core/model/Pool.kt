package org.nexadex.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PoolStatus {
    DEPLOYING,
    ACTIVE,
    PAUSED,
    DRAINED,
}

@Serializable
data class Pool(
    val poolId: Int = 0,
    val tokenGroupIdHex: String,
    val lpGroupIdHex: String,
    val initialLpSupply: Long = 1_000_000_000L,
    val contractAddress: String,
    val contractBlob: ByteArray = ByteArray(0),
    val status: PoolStatus = PoolStatus.DEPLOYING,
    val nexReserve: Long = 0L,
    val tokenReserve: Long = 0L,
    val deployTxId: String? = null,
    val poolUtxoTxId: String? = null,
    val poolUtxoVout: Int? = null,
    val contractVersion: String = "v3",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Pool) return false
        return poolId == other.poolId
    }

    override fun hashCode(): Int = poolId
}
