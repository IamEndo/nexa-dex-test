package org.nexadex.api

import org.nexadex.api.middleware.RateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class RateLimiterTest {

    @Test
    fun `new key starts with full tokens`() {
        val limiter = RateLimiter(maxTokens = 10, refillPerSecond = 1.0)
        assertEquals(10, limiter.remaining("new-key"))
    }

    @Test
    fun `consume reduces remaining`() {
        val limiter = RateLimiter(maxTokens = 5, refillPerSecond = 0.0)
        assertTrue(limiter.tryConsume("key1"))
        // After consuming 1, should have ~4
        assertTrue(limiter.remaining("key1") <= 4)
    }

    @Test
    fun `exhaust tokens then fail`() {
        val limiter = RateLimiter(maxTokens = 3, refillPerSecond = 0.0)
        assertTrue(limiter.tryConsume("key1"))
        assertTrue(limiter.tryConsume("key1"))
        assertTrue(limiter.tryConsume("key1"))
        assertFalse(limiter.tryConsume("key1"), "Should be exhausted")
    }

    @Test
    fun `different keys have independent buckets`() {
        val limiter = RateLimiter(maxTokens = 2, refillPerSecond = 0.0)
        assertTrue(limiter.tryConsume("key1"))
        assertTrue(limiter.tryConsume("key1"))
        assertFalse(limiter.tryConsume("key1"))
        // key2 should still have tokens
        assertTrue(limiter.tryConsume("key2"))
    }

    @Test
    fun `limit returns max tokens`() {
        val limiter = RateLimiter(maxTokens = 100, refillPerSecond = 1.0)
        assertEquals(100, limiter.limit())
    }

    @Test
    fun `remaining for unknown key returns max`() {
        val limiter = RateLimiter(maxTokens = 50, refillPerSecond = 1.0)
        assertEquals(50, limiter.remaining("never-seen"))
    }

    @Test
    fun `tokens refill over time`() {
        val limiter = RateLimiter(maxTokens = 10, refillPerSecond = 1000.0) // Very fast refill
        // Exhaust all tokens
        repeat(10) { assertTrue(limiter.tryConsume("key1")) }
        // With 1000/sec refill, even a tiny delay should refill
        Thread.sleep(10) // 10ms
        assertTrue(limiter.tryConsume("key1"), "Should have refilled")
    }

    @Test
    fun `tokens don't exceed max`() {
        val limiter = RateLimiter(maxTokens = 5, refillPerSecond = 10000.0)
        Thread.sleep(50) // Wait for massive refill
        // Should still be capped at 5
        assertTrue(limiter.remaining("key1") <= 5)
    }
}
