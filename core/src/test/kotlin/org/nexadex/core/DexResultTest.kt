package org.nexadex.core

import org.nexadex.core.error.DexError
import org.nexadex.core.error.DexResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DexResultTest {

    @Test
    fun `success - basic operations`() {
        val result = DexResult.success(42)
        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
        assertEquals(42, result.getOrNull())
        assertEquals(42, result.getOrThrow())
        assertNull(result.errorOrNull())
    }

    @Test
    fun `failure - basic operations`() {
        val error = DexError.PoolNotFound(1)
        val result = DexResult.failure(error)
        assertFalse(result.isSuccess)
        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
        assertEquals(error, result.errorOrNull())
    }

    @Test
    fun `map - transforms success`() {
        val result = DexResult.success(10).map { it * 2 }
        assertEquals(20, result.getOrThrow())
    }

    @Test
    fun `map - preserves failure`() {
        val result: DexResult<Int> = DexResult.failure(DexError.PoolNotFound(1))
        val mapped = result.map { it * 2 }
        assertTrue(mapped.isFailure)
    }

    @Test
    fun `flatMap - chains success`() {
        val result = DexResult.success(10)
            .flatMap { DexResult.success(it * 2) }
        assertEquals(20, result.getOrThrow())
    }

    @Test
    fun `flatMap - short circuits on failure`() {
        val result = DexResult.success(10)
            .flatMap<Int> { DexResult.failure(DexError.TradeTimeout(1)) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `onSuccess callback fires for success`() {
        var captured = 0
        DexResult.success(42).onSuccess { captured = it }
        assertEquals(42, captured)
    }

    @Test
    fun `onFailure callback fires for failure`() {
        var captured: DexError? = null
        DexResult.failure(DexError.RateLimited()).onFailure { captured = it }
        assertTrue(captured is DexError.RateLimited)
    }

    @Test
    fun `error types have correct properties`() {
        val poolNotFound = DexError.PoolNotFound(42)
        assertEquals("POOL_NOT_FOUND", poolNotFound.type)
        assertFalse(poolNotFound.retryable)

        val rateLimited = DexError.RateLimited()
        assertEquals("RATE_LIMITED", rateLimited.type)
        assertTrue(rateLimited.retryable)

        val broadcastFailed = DexError.BroadcastFailed("test")
        assertTrue(broadcastFailed.retryable)
    }
}
