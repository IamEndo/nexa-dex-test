package org.nexadex.core

import org.nexadex.core.math.AmmMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AmmMathTest {

    @Test
    fun `computeSwapOutput - basic constant product`() {
        // Pool: 10000 NEX, 50000 tokens
        // Sell 1000 tokens: Δnex = 10000 × 1000 / (50000 + 1000) = 196
        val out = AmmMath.computeSwapOutput(1000, 50000, 10000)
        assertNotNull(out)
        assertTrue(out > 0)
        assertTrue(out < 10000) // can't drain entire reserve
    }

    @Test
    fun `computeSwapOutput - zero or negative inputs return null`() {
        assertNull(AmmMath.computeSwapOutput(0, 50000, 10000))
        assertNull(AmmMath.computeSwapOutput(-1, 50000, 10000))
        assertNull(AmmMath.computeSwapOutput(1000, 0, 10000))
        assertNull(AmmMath.computeSwapOutput(1000, 50000, 0))
    }

    @Test
    fun `computeSellOutput - tokens to NEX`() {
        val nexReserve = 100_000L // 1000 NEX
        val tokenReserve = 500_000L

        val nexOut = AmmMath.computeSellOutput(10_000, nexReserve, tokenReserve)
        assertNotNull(nexOut)
        assertTrue(nexOut > 0)
        assertTrue(nexOut < nexReserve)
    }

    @Test
    fun `computeBuyOutput - NEX to tokens`() {
        val nexReserve = 100_000L
        val tokenReserve = 500_000L

        val tokensOut = AmmMath.computeBuyOutput(5_000, nexReserve, tokenReserve)
        assertNotNull(tokensOut)
        assertTrue(tokensOut > 0)
        assertTrue(tokensOut < tokenReserve)
    }

    @Test
    fun `spotPrice calculation`() {
        val price = AmmMath.spotPrice(100_000, 500_000)
        assertNotNull(price)
        assertEquals(0.2, price, 0.001)
    }

    @Test
    fun `spotPrice - zero reserves return null`() {
        assertNull(AmmMath.spotPrice(0, 100))
        assertNull(AmmMath.spotPrice(100, 0))
    }

    @Test
    fun `computePriceImpactBps - small trade has low impact`() {
        val nexReserve = 1_000_000L
        val tokenReserve = 5_000_000L

        // Small sell: 100 tokens
        val out = AmmMath.computeSellOutput(100, nexReserve, tokenReserve)!!
        val impact = AmmMath.computePriceImpactBps(100, out, tokenReserve, nexReserve)
        assertNotNull(impact)
        assertTrue(impact <= 500, "Impact for tiny trade should be <= 5%, got ${impact}bps") // small trade
    }

    @Test
    fun `computePriceImpactBps - large trade has high impact`() {
        val nexReserve = 100_000L
        val tokenReserve = 500_000L

        // Large sell: 250000 tokens (50% of reserve)
        val out = AmmMath.computeSellOutput(250_000, nexReserve, tokenReserve)!!
        val impact = AmmMath.computePriceImpactBps(250_000, out, tokenReserve, nexReserve)
        assertNotNull(impact)
        assertTrue(impact > 1000) // > 10% impact
    }

    @Test
    fun `computeMinimumReceived - 1% slippage`() {
        val expected = 10_000L
        val min = AmmMath.computeMinimumReceived(expected, 100) // 100 bps = 1%
        assertEquals(9900, min)
    }

    @Test
    fun `computeMinimumReceived - zero slippage returns full amount`() {
        assertEquals(10_000, AmmMath.computeMinimumReceived(10_000, 0))
    }

    @Test
    fun `computeTvl - balanced AMM`() {
        assertEquals(200_000, AmmMath.computeTvl(100_000))
    }

    @Test
    fun `reservesAfterSell - reserves update correctly`() {
        val nexReserve = 100_000L
        val tokenReserve = 500_000L
        val tokenIn = 10_000L

        val result = AmmMath.reservesAfterSell(tokenIn, nexReserve, tokenReserve)
        assertNotNull(result)

        val (newNex, newToken) = result
        assertTrue(newNex < nexReserve) // NEX went out
        assertEquals(tokenReserve + tokenIn, newToken) // tokens came in

        // K should be approximately preserved (may decrease slightly due to integer truncation)
        val kBefore = nexReserve.toBigInteger() * tokenReserve.toBigInteger()
        val kAfter = newNex.toBigInteger() * newToken.toBigInteger()
        // With no-fee formula, k decreases slightly due to integer division truncation
        val drift = (kBefore - kAfter).toDouble() / kBefore.toDouble()
        assertTrue(drift < 0.01, "K drift should be < 1%, was ${drift * 100}%")
    }

    @Test
    fun `reservesAfterBuy - reserves update correctly`() {
        val nexReserve = 100_000L
        val tokenReserve = 500_000L
        val nexIn = 5_000L

        val result = AmmMath.reservesAfterBuy(nexIn, nexReserve, tokenReserve)
        assertNotNull(result)

        val (newNex, newToken) = result
        assertEquals(nexReserve + nexIn, newNex) // NEX came in
        assertTrue(newToken < tokenReserve) // tokens went out
    }

    @Test
    fun `validateReservesAfterTrade - dust threshold`() {
        assertTrue(AmmMath.validateReservesAfterTrade(1000, 1))
        assertTrue(AmmMath.validateReservesAfterTrade(546, 1))
        assertFalse(AmmMath.validateReservesAfterTrade(545, 1)) // below dust
        assertFalse(AmmMath.validateReservesAfterTrade(1000, 0)) // zero tokens
    }

    @Test
    fun `constant product invariant - k approximately preserved`() {
        var nexReserve = 1_000_000L
        var tokenReserve = 5_000_000L
        val kOriginal = nexReserve.toBigInteger() * tokenReserve.toBigInteger()

        // Execute 100 alternating trades
        repeat(50) {
            // Sell 1000 tokens
            val sellResult = AmmMath.reservesAfterSell(1000, nexReserve, tokenReserve)!!
            nexReserve = sellResult.first
            tokenReserve = sellResult.second

            // Buy with 200 NEX
            val buyResult = AmmMath.reservesAfterBuy(200, nexReserve, tokenReserve)!!
            nexReserve = buyResult.first
            tokenReserve = buyResult.second
        }

        // After many trades, k may drift slightly due to integer truncation but should stay close
        val kFinal = nexReserve.toBigInteger() * tokenReserve.toBigInteger()
        val drift = (kOriginal - kFinal).abs().toDouble() / kOriginal.toDouble()
        assertTrue(drift < 0.05, "K drift after 100 trades should be < 5%, was ${drift * 100}%")
    }

    @Test
    fun `swap output matches no-fee constant product formula`() {
        // No-fee output: reserveOut * amountIn / (reserveIn + amountIn)
        // = 10000 * 1000 / (50000 + 1000) = 196 (integer division)
        val output = AmmMath.computeSwapOutput(1000, 50000, 10000, feeBps = 0)!!
        val expected = 10000L * 1000L / (50000L + 1000L)
        assertEquals(expected, output, "Output should match no-fee constant product formula")
    }

    @Test
    fun `large values - no overflow`() {
        // 10B satoshis each (100M NEX)
        val out = AmmMath.computeSwapOutput(1_000_000_000, 10_000_000_000, 10_000_000_000)
        assertNotNull(out)
        assertTrue(out > 0)
    }
}
