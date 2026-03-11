package org.nexadex.core.error

sealed class DexError(
    val type: String,
    override val message: String,
    val retryable: Boolean = false,
) : Exception(message) {

    // Pool errors
    class PoolNotFound(val poolId: Int) :
        DexError("POOL_NOT_FOUND", "Pool $poolId not found")

    class PoolNotActive(val poolId: Int, val status: String) :
        DexError("POOL_NOT_ACTIVE", "Pool $poolId is $status, not ACTIVE")

    class PoolAlreadyExists(val tokenGroupIdHex: String) :
        DexError("POOL_ALREADY_EXISTS", "Pool for token $tokenGroupIdHex already exists")

    class PoolDeployFailed(val reason: String) :
        DexError("POOL_DEPLOY_FAILED", "Pool deployment failed: $reason")

    // Trade errors
    class InsufficientLiquidity(val required: Long, val available: Long) :
        DexError("INSUFFICIENT_LIQUIDITY", "Insufficient liquidity: need $required, have $available")

    class SlippageExceeded(val expectedBps: Int, val actualBps: Int) :
        DexError("SLIPPAGE_EXCEEDED", "Slippage $actualBps bps exceeds max $expectedBps bps")

    class PriceImpactTooHigh(val impactBps: Int, val maxBps: Int) :
        DexError("PRICE_IMPACT_TOO_HIGH", "Price impact $impactBps bps exceeds max $maxBps bps")

    class TradeTooSmall(val amount: Long, val minimum: Long) :
        DexError("TRADE_TOO_SMALL", "Trade amount $amount below minimum $minimum")

    class TradeTimeout(val poolId: Int) :
        DexError("TRADE_TIMEOUT", "Trade on pool $poolId timed out", retryable = true)

    class BroadcastFailed(val reason: String) :
        DexError("BROADCAST_FAILED", "Transaction broadcast failed: $reason", retryable = true)

    // Token errors
    class TokenNotFound(val groupIdHex: String) :
        DexError("TOKEN_NOT_FOUND", "Token $groupIdHex not registered")

    class TokenAlreadyRegistered(val groupIdHex: String) :
        DexError("TOKEN_ALREADY_REGISTERED", "Token $groupIdHex already registered")

    // Reserve errors
    class ReserveMismatch(val poolId: Int, val expected: Long, val actual: Long) :
        DexError("RESERVE_MISMATCH", "Pool $poolId reserve mismatch: DB=$expected, chain=$actual")

    // Connection errors
    class ConnectionFailed(val reason: String) :
        DexError("CONNECTION_FAILED", "Rostrum connection failed: $reason", retryable = true)

    class NotConnected :
        DexError("NOT_CONNECTED", "Not connected to Rostrum server", retryable = true)

    // Rate limiting
    class RateLimited :
        DexError("RATE_LIMITED", "Too many requests", retryable = true)

    // V2 errors
    class InvalidOperation(reason: String) :
        DexError("INVALID_OPERATION", reason)

    // Config errors
    class ConfigError(reason: String) :
        DexError("CONFIG_ERROR", "Configuration error: $reason")

    // General
    class InternalError(reason: String) :
        DexError("INTERNAL_ERROR", "Internal error: $reason")
}
