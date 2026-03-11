package org.nexadex.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreatePoolRequest(
    val tokenGroupIdHex: String,
    val lpGroupIdHex: String = "",
    val initialLpSupply: Long = 1_000_000_000L,
    val initialNexSats: Long,
    val initialTokenAmount: Long,
    val mnemonic: String,
)

@Serializable
data class RegisterTokenRequest(
    val groupIdHex: String,
    val name: String? = null,
    val ticker: String? = null,
    val decimals: Int = 0,
    val documentUrl: String? = null,
)

@Serializable
data class RegisterPoolRequest(
    val tokenGroupIdHex: String,
    val lpGroupIdHex: String,
    val initialLpSupply: Long = 1_000_000_000L,
    val contractAddress: String,
    val deployTxId: String,
    val nexReserve: Long,
    val tokenReserve: Long,
)

@Serializable
data class AddLiquidityRequest(
    val poolId: Int,
    val nexSats: Long,
    val tokenAmount: Long,
    val mnemonic: String = "",
    val cookie: String = "",
)

@Serializable
data class RemoveLiquidityRequest(
    val poolId: Int,
    val lpTokenAmount: Long,
    val mnemonic: String = "",
    val cookie: String = "",
)
