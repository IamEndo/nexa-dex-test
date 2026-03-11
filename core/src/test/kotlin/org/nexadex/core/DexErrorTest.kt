package org.nexadex.core

import org.nexadex.core.error.DexError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs

class DexErrorTest {

    // ── Pool errors ──

    @Test
    fun `PoolNotFound - not retryable`() {
        val error = DexError.PoolNotFound(42)
        assertEquals("POOL_NOT_FOUND", error.type)
        assertFalse(error.retryable)
        assertTrue(error.message.contains("42"))
    }

    @Test
    fun `PoolNotActive - not retryable`() {
        val error = DexError.PoolNotActive(1, "PAUSED")
        assertEquals("POOL_NOT_ACTIVE", error.type)
        assertFalse(error.retryable)
        assertTrue(error.message.contains("PAUSED"))
    }

    @Test
    fun `PoolAlreadyExists - not retryable`() {
        val error = DexError.PoolAlreadyExists("aabb")
        assertEquals("POOL_ALREADY_EXISTS", error.type)
        assertFalse(error.retryable)
        assertTrue(error.message.contains("aabb"))
    }

    @Test
    fun `PoolDeployFailed - not retryable`() {
        val error = DexError.PoolDeployFailed("timeout")
        assertEquals("POOL_DEPLOY_FAILED", error.type)
        assertFalse(error.retryable)
    }

    // ── Trade errors ──

    @Test
    fun `InsufficientLiquidity - not retryable`() {
        val error = DexError.InsufficientLiquidity(10000, 5000)
        assertEquals("INSUFFICIENT_LIQUIDITY", error.type)
        assertFalse(error.retryable)
        assertTrue(error.message.contains("10000"))
    }

    @Test
    fun `SlippageExceeded - not retryable`() {
        val error = DexError.SlippageExceeded(150, 100)
        assertEquals("SLIPPAGE_EXCEEDED", error.type)
        assertFalse(error.retryable)
        assertTrue(error.message.contains("150"))
        assertTrue(error.message.contains("100"))
    }

    @Test
    fun `PriceImpactTooHigh - not retryable`() {
        val error = DexError.PriceImpactTooHigh(2000, 1500)
        assertEquals("PRICE_IMPACT_TOO_HIGH", error.type)
        assertFalse(error.retryable)
    }

    @Test
    fun `TradeTooSmall - not retryable`() {
        val error = DexError.TradeTooSmall(100, 546)
        assertEquals("TRADE_TOO_SMALL", error.type)
        assertFalse(error.retryable)
    }

    @Test
    fun `TradeTimeout - retryable`() {
        val error = DexError.TradeTimeout(1)
        assertEquals("TRADE_TIMEOUT", error.type)
        assertTrue(error.retryable)
    }

    @Test
    fun `BroadcastFailed - retryable`() {
        val error = DexError.BroadcastFailed("mempool full")
        assertEquals("BROADCAST_FAILED", error.type)
        assertTrue(error.retryable)
    }

    // ── Token errors ──

    @Test
    fun `TokenNotFound - not retryable`() {
        val error = DexError.TokenNotFound("aabb")
        assertEquals("TOKEN_NOT_FOUND", error.type)
        assertFalse(error.retryable)
    }

    @Test
    fun `TokenAlreadyRegistered - not retryable`() {
        val error = DexError.TokenAlreadyRegistered("aabb")
        assertEquals("TOKEN_ALREADY_REGISTERED", error.type)
        assertFalse(error.retryable)
    }

    // ── Connection errors ──

    @Test
    fun `ConnectionFailed - retryable`() {
        val error = DexError.ConnectionFailed("connection refused")
        assertEquals("CONNECTION_FAILED", error.type)
        assertTrue(error.retryable)
    }

    @Test
    fun `NotConnected - retryable`() {
        val error = DexError.NotConnected()
        assertEquals("NOT_CONNECTED", error.type)
        assertTrue(error.retryable)
    }

    // ── Other errors ──

    @Test
    fun `RateLimited - retryable`() {
        val error = DexError.RateLimited()
        assertEquals("RATE_LIMITED", error.type)
        assertTrue(error.retryable)
    }

    @Test
    fun `ConfigError - not retryable`() {
        val error = DexError.ConfigError("bad config")
        assertEquals("CONFIG_ERROR", error.type)
        assertFalse(error.retryable)
    }

    @Test
    fun `InternalError - not retryable`() {
        val error = DexError.InternalError("oops")
        assertEquals("INTERNAL_ERROR", error.type)
        assertFalse(error.retryable)
    }

    @Test
    fun `ReserveMismatch - not retryable`() {
        val error = DexError.ReserveMismatch(1, 100000, 99500)
        assertEquals("RESERVE_MISMATCH", error.type)
        assertFalse(error.retryable)
    }

    @Test
    fun `InvalidOperation - not retryable`() {
        val error = DexError.InvalidOperation("not a V2 pool")
        assertEquals("INVALID_OPERATION", error.type)
        assertFalse(error.retryable)
    }

    // ── DexError is an Exception ──

    @Test
    fun `DexError can be thrown and caught`() {
        try {
            throw DexError.PoolNotFound(1)
        } catch (e: DexError) {
            assertIs<DexError.PoolNotFound>(e)
            assertEquals("POOL_NOT_FOUND", e.type)
        }
    }

    @Test
    fun `all error types count`() {
        val errors: List<DexError> = listOf(
            DexError.PoolNotFound(1),
            DexError.PoolNotActive(1, "PAUSED"),
            DexError.PoolAlreadyExists("aa"),
            DexError.PoolDeployFailed("fail"),
            DexError.InsufficientLiquidity(1, 1),
            DexError.SlippageExceeded(1, 1),
            DexError.PriceImpactTooHigh(1, 1),
            DexError.TradeTooSmall(1, 1),
            DexError.TradeTimeout(1),
            DexError.BroadcastFailed("fail"),
            DexError.TokenNotFound("aa"),
            DexError.TokenAlreadyRegistered("aa"),
            DexError.ReserveMismatch(1, 1, 1),
            DexError.ConnectionFailed("fail"),
            DexError.NotConnected(),
            DexError.RateLimited(),
            DexError.InvalidOperation("test"),
            DexError.ConfigError("bad"),
            DexError.InternalError("oops"),
        )
        assertEquals(19, errors.size)
        val types = errors.map { it.type }.toSet()
        assertEquals(19, types.size, "All error types should be unique")
    }
}
