package org.nexadex.core

import org.nexadex.core.math.AmmMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Edge case and stress tests for AMM math.
 */
class AmmMathEdgeCaseTest {

    @Test
    fun `computeSwapOutput - exact value for known inputs (no fee)`() {
        // No-fee: Δout = reserveOut × amountIn / (reserveIn + amountIn)
        // = 10000 * 1000 / (50000 + 1000) = 196 (integer division)
        val out = AmmMath.computeSwapOutput(1000, 50000, 10000, feeBps = 0)
        assertNotNull(out)
        assertEquals(196L, out)
    }

    @Test
    fun `computeSwapOutput - exact value with default fee`() {
        // With 0.3% fee: amountInAfterFee = 1000 * 9970 / 10000 = 997
        // Δout = 10000 * 997 / (50000 + 997) = 9970000 / 50997 = 195
        val out = AmmMath.computeSwapOutput(1000, 50000, 10000)
        assertNotNull(out)
        assertEquals(195L, out)
    }

    @Test
    fun `computeSwapOutput - symmetric inputs`() {
        // Equal reserves: swap 1000 from 100000/100000 pool
        val out = AmmMath.computeSwapOutput(1000, 100000, 100000)
        assertNotNull(out)
        assertTrue(out > 0)
        assertTrue(out < 1000) // slippage from constant product
    }

    @Test
    fun `computeSwapOutput - very small input rounds to zero`() {
        val out = AmmMath.computeSwapOutput(1, 1_000_000, 1_000_000)
        // 1000000 * 1 / (1000000 + 1) = 0 (integer division)
        // Output is 0 → returns null (must be > 0)
        assertNull(out)
    }

    @Test
    fun `computeSwapOutput - input equal to reserve`() {
        // Trading amount equal to entire input reserve
        val out = AmmMath.computeSwapOutput(100000, 100000, 100000)
        assertNotNull(out)
        assertTrue(out > 0)
        assertTrue(out < 100000) // Can't drain
        // With 0.3% fee: amountInAfterFee = 100000 * 9970 / 10000 = 99700
        // output = 100000 * 99700 / (100000 + 99700) = 49924
        assertEquals(49924L, out)
    }

    @Test
    fun `spotPrice - ratio is nexReserve over tokenReserve`() {
        assertEquals(0.2, AmmMath.spotPrice(100_000, 500_000)!!, 0.001)
        assertEquals(1.0, AmmMath.spotPrice(100_000, 100_000)!!, 0.001)
        assertEquals(5.0, AmmMath.spotPrice(500_000, 100_000)!!, 0.001)
        assertEquals(0.01, AmmMath.spotPrice(1000, 100_000)!!, 0.001)
    }

    @Test
    fun `computeTvl - simple doubling`() {
        assertEquals(0L, AmmMath.computeTvl(0))
        assertEquals(2L, AmmMath.computeTvl(1))
        assertEquals(200_000L, AmmMath.computeTvl(100_000))
        assertEquals(20_000_000_000L, AmmMath.computeTvl(10_000_000_000L))
    }

    @Test
    fun `computeMinimumReceived - various slippage values`() {
        assertEquals(10000L, AmmMath.computeMinimumReceived(10000, 0))
        assertEquals(9950L, AmmMath.computeMinimumReceived(10000, 50))  // 0.5%
        assertEquals(9900L, AmmMath.computeMinimumReceived(10000, 100)) // 1%
        assertEquals(9700L, AmmMath.computeMinimumReceived(10000, 300)) // 3%
        assertEquals(9500L, AmmMath.computeMinimumReceived(10000, 500)) // 5%
        assertEquals(5000L, AmmMath.computeMinimumReceived(10000, 5000)) // 50%
        assertEquals(0L, AmmMath.computeMinimumReceived(10000, 10000))   // 100%
    }

    @Test
    fun `validateReservesAfterTrade - boundary at dust threshold`() {
        assertTrue(AmmMath.validateReservesAfterTrade(546, 1))
        assertFalse(AmmMath.validateReservesAfterTrade(545, 1))
        assertFalse(AmmMath.validateReservesAfterTrade(0, 1))
        assertFalse(AmmMath.validateReservesAfterTrade(1000, 0))
        assertFalse(AmmMath.validateReservesAfterTrade(-1, 1))
        assertFalse(AmmMath.validateReservesAfterTrade(1000, -1))
        assertTrue(AmmMath.validateReservesAfterTrade(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun `reservesAfterSell - multiple sequential sells`() {
        var nex = 100_000L
        var token = 500_000L
        repeat(10) {
            val result = AmmMath.reservesAfterSell(100, nex, token)
            assertNotNull(result)
            assertTrue(result.first < nex) // NEX decreased
            assertEquals(token + 100, result.second) // Tokens increased
            nex = result.first
            token = result.second
        }
        // NEX should have decreased significantly
        assertTrue(nex < 100_000)
        // Tokens should have increased by 1000 total
        assertEquals(501_000, token)
    }

    @Test
    fun `reservesAfterBuy - multiple sequential buys`() {
        var nex = 100_000L
        var token = 500_000L
        repeat(10) {
            val result = AmmMath.reservesAfterBuy(100, nex, token)
            assertNotNull(result)
            assertEquals(nex + 100, result.first) // NEX increased
            assertTrue(result.second < token) // Tokens decreased
            nex = result.first
            token = result.second
        }
        assertTrue(nex == 101_000L)
        assertTrue(token < 500_000)
    }

    @Test
    fun `sell then buy should not be profitable (arb protection)`() {
        val nex = 100_000L
        val token = 500_000L

        // Sell 10000 tokens
        val afterSell = AmmMath.reservesAfterSell(10000, nex, token)!!
        val nexReceived = nex - afterSell.first

        // Buy back tokens with the NEX received
        val afterBuy = AmmMath.reservesAfterBuy(nexReceived, afterSell.first, afterSell.second)!!
        val tokensReceived = afterSell.second - afterBuy.second

        // Should receive fewer tokens than originally sold (integer truncation each way)
        assertTrue(tokensReceived <= 10000, "Should not profit from round-trip: sold 10000, got back $tokensReceived")
    }

    @Test
    fun `computeSellOutput null for zero inputs`() {
        assertNull(AmmMath.computeSellOutput(0, 100, 100))
        assertNull(AmmMath.computeSellOutput(100, 0, 100))
        assertNull(AmmMath.computeSellOutput(100, 100, 0))
        assertNull(AmmMath.computeSellOutput(-1, 100, 100))
    }

    @Test
    fun `computeBuyOutput null for zero inputs`() {
        assertNull(AmmMath.computeBuyOutput(0, 100, 100))
        assertNull(AmmMath.computeBuyOutput(100, 0, 100))
        assertNull(AmmMath.computeBuyOutput(100, 100, 0))
        assertNull(AmmMath.computeBuyOutput(-1, 100, 100))
    }

    @Test
    fun `price impact - large trade has more impact than small`() {
        val nex = 1_000_000L
        val token = 5_000_000L

        // Small trade
        val smallOut = AmmMath.computeSellOutput(1000, nex, token)!!
        val smallImpact = AmmMath.computePriceImpactBps(1000, smallOut, token, nex)!!

        // Large trade (100x bigger)
        val largeOut = AmmMath.computeSellOutput(100_000, nex, token)!!
        val largeImpact = AmmMath.computePriceImpactBps(100_000, largeOut, token, nex)!!

        assertTrue(largeImpact > smallImpact,
            "Large trade impact ($largeImpact bps) should exceed small trade impact ($smallImpact bps)")
    }
}
