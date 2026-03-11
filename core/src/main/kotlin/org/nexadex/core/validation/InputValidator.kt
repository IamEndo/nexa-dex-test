package org.nexadex.core.validation

import org.nexadex.core.error.DexError
import org.nexadex.core.error.DexResult
import org.nexadex.core.math.AmmMath

/**
 * Input validation for trade requests.
 */
object InputValidator {

    private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")
    private val NEXA_ADDRESS_REGEX = Regex("^nexa:[a-z0-9]{30,120}$")

    fun validateAddress(address: String): DexResult<String> {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) {
            return DexResult.failure(DexError.ConfigError("Address is empty"))
        }
        if (!NEXA_ADDRESS_REGEX.matches(trimmed)) {
            return DexResult.failure(DexError.ConfigError("Invalid Nexa address format"))
        }
        return DexResult.success(trimmed)
    }

    fun validateGroupIdHex(hex: String): DexResult<String> {
        val trimmed = hex.trim().lowercase()
        if (trimmed.length != 64) {
            return DexResult.failure(DexError.ConfigError("Group ID must be 64 hex characters, got ${trimmed.length}"))
        }
        if (!HEX_REGEX.matches(trimmed)) {
            return DexResult.failure(DexError.ConfigError("Group ID contains non-hex characters"))
        }
        return DexResult.success(trimmed)
    }

    fun validateTradeAmount(amount: Long, direction: String): DexResult<Long> {
        if (amount <= 0) {
            return DexResult.failure(DexError.TradeTooSmall(amount, 1))
        }
        if (amount > 10_000_000_000_000L) { // 100B sats = 1B NEX — sanity limit
            return DexResult.failure(DexError.ConfigError("Amount exceeds maximum: $amount"))
        }
        return DexResult.success(amount)
    }

    fun validateSlippageBps(bps: Int): DexResult<Int> {
        if (bps < 0) {
            return DexResult.failure(DexError.ConfigError("Slippage cannot be negative"))
        }
        if (bps > 5000) { // max 50%
            return DexResult.failure(DexError.ConfigError("Slippage $bps bps exceeds maximum 5000 bps (50%)"))
        }
        return DexResult.success(bps)
    }

    fun validatePoolId(poolId: Int): DexResult<Int> {
        if (poolId <= 0) {
            return DexResult.failure(DexError.PoolNotFound(poolId))
        }
        return DexResult.success(poolId)
    }
}
