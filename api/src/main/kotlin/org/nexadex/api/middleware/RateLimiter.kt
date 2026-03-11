package org.nexadex.api.middleware

import java.util.concurrent.ConcurrentHashMap

class RateLimiter(
    private val maxTokens: Int = 120,
    private val refillPerSecond: Double = 2.0,
) {
    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    fun tryConsume(key: String): Boolean =
        buckets.computeIfAbsent(key) { TokenBucket(maxTokens, refillPerSecond) }.tryConsume()

    fun remaining(key: String): Int =
        buckets[key]?.remaining() ?: maxTokens

    fun limit(): Int = maxTokens

    private class TokenBucket(
        private val max: Int,
        private val refillPerSecond: Double,
    ) {
        private var tokens: Double = max.toDouble()
        private var lastRefill: Long = System.nanoTime()

        @Synchronized
        fun tryConsume(): Boolean {
            refill()
            return if (tokens >= 1.0) {
                tokens -= 1.0
                true
            } else {
                false
            }
        }

        @Synchronized
        fun remaining(): Int {
            refill()
            return tokens.toInt()
        }

        private fun refill() {
            val now = System.nanoTime()
            val elapsed = (now - lastRefill) / 1_000_000_000.0
            tokens = (tokens + elapsed * refillPerSecond).coerceAtMost(max.toDouble())
            lastRefill = now
        }
    }
}
