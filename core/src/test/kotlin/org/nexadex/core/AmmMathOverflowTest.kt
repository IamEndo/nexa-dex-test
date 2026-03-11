package org.nexadex.core

import org.nexadex.core.math.AmmMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for overflow protection and liquidity math in AmmMath.
 */
class AmmMathOverflowTest {

    @Test
    fun `computeSwapOutput - large values that would overflow Long`() {
        // 10 trillion * 997 would overflow Long.MAX_VALUE (9.2 * 10^18)
        val amountIn = 10_000_000_000_000L // 10T
        val reserveIn = 50_000_000_000_000L // 50T
        val reserveOut = 50_000_000_000_000L // 50T
        val out = AmmMath.computeSwapOutput(amountIn, reserveIn, reserveOut)
        assertNotNull(out, "Should handle large values without overflow")
        assertTrue(out > 0)
        assertTrue(out < reserveOut, "Output must be less than reserve")
    }

    @Test
    fun `computeSwapOutput - max safe values`() {
        // Use values near Long.MAX_VALUE / 1000 to test boundary
        val amountIn = Long.MAX_VALUE / 10000
        val reserveIn = Long.MAX_VALUE / 1000
        val reserveOut = Long.MAX_VALUE / 1000
        val out = AmmMath.computeSwapOutput(amountIn, reserveIn, reserveOut)
        assertNotNull(out, "Should handle near-max values with BigInteger")
        assertTrue(out > 0)
    }

    @Test
    fun `computeProportionalTokenAmount - large reserves no overflow`() {
        val nexAmountIn = 1_000_000_000_000L // 1T
        val nexReserve = 5_000_000_000_000L // 5T
        val tokenReserve = 10_000_000_000_000L // 10T
        val result = AmmMath.computeProportionalTokenAmount(nexAmountIn, nexReserve, tokenReserve)
        assertNotNull(result)
        assertEquals(2_000_000_000_000L, result) // 1T * 10T / 5T = 2T
    }

    @Test
    fun `computeProportionalTokenAmount - basic ratio`() {
        assertEquals(500L, AmmMath.computeProportionalTokenAmount(100, 1000, 5000))
        assertEquals(100L, AmmMath.computeProportionalTokenAmount(100, 1000, 1000))
        assertEquals(50L, AmmMath.computeProportionalTokenAmount(100, 1000, 500))
    }

    @Test
    fun `computeProportionalTokenAmount - invalid inputs`() {
        assertNull(AmmMath.computeProportionalTokenAmount(0, 1000, 5000))
        assertNull(AmmMath.computeProportionalTokenAmount(-1, 1000, 5000))
        assertNull(AmmMath.computeProportionalTokenAmount(100, 0, 5000))
        assertNull(AmmMath.computeProportionalTokenAmount(100, 1000, 0))
    }

    @Test
    fun `computeWithdrawalAmounts - basic percentages`() {
        val (nex, token) = AmmMath.computeWithdrawalAmounts(50.0, 100_000, 500_000)!!
        assertEquals(50_000L, nex)
        assertEquals(250_000L, token)
    }

    @Test
    fun `computeWithdrawalAmounts - 100 percent`() {
        val (nex, token) = AmmMath.computeWithdrawalAmounts(100.0, 100_000, 500_000)!!
        assertEquals(100_000L, nex)
        assertEquals(500_000L, token)
    }

    @Test
    fun `computeWithdrawalAmounts - small percentage`() {
        val (nex, token) = AmmMath.computeWithdrawalAmounts(1.0, 100_000, 500_000)!!
        assertEquals(1000L, nex)
        assertEquals(5000L, token)
    }

    @Test
    fun `computeWithdrawalAmounts - invalid inputs`() {
        assertNull(AmmMath.computeWithdrawalAmounts(0.0, 100_000, 500_000))
        assertNull(AmmMath.computeWithdrawalAmounts(-5.0, 100_000, 500_000))
        assertNull(AmmMath.computeWithdrawalAmounts(101.0, 100_000, 500_000))
        assertNull(AmmMath.computeWithdrawalAmounts(50.0, 0, 500_000))
        assertNull(AmmMath.computeWithdrawalAmounts(50.0, 100_000, 0))
    }

    @Test
    fun `computeWithdrawalAmounts - very small pool returns null`() {
        // 1% of 10 sats = 0 sats, should return null
        assertNull(AmmMath.computeWithdrawalAmounts(1.0, 10, 10))
    }

    @Test
    fun `constant product invariant approximately maintained after swap`() {
        val nexReserve = 1_000_000L
        val tokenReserve = 5_000_000L
        val k = nexReserve.toBigInteger() * tokenReserve.toBigInteger()

        val amountIn = 10_000L
        val (newNex, newToken) = AmmMath.reservesAfterSell(amountIn, nexReserve, tokenReserve)!!

        val newK = newNex.toBigInteger() * newToken.toBigInteger()
        // k may decrease slightly due to integer truncation (no fee)
        val drift = (k - newK).abs().toDouble() / k.toDouble()
        assertTrue(drift < 0.001, "k drift should be < 0.1% after single trade, was ${drift * 100}%")
    }

    @Test
    fun `constant product invariant - buy direction`() {
        val nexReserve = 1_000_000L
        val tokenReserve = 5_000_000L
        val k = nexReserve.toBigInteger() * tokenReserve.toBigInteger()

        val amountIn = 50_000L
        val (newNex, newToken) = AmmMath.reservesAfterBuy(amountIn, nexReserve, tokenReserve)!!

        val newK = newNex.toBigInteger() * newToken.toBigInteger()
        val drift = (k - newK).abs().toDouble() / k.toDouble()
        assertTrue(drift < 0.01, "k drift should be < 1% after single buy, was ${drift * 100}%")
    }

    @Test
    fun `swap output matches exact no-fee formula`() {
        val reserve = 1_000_000L
        // No-fee: reserveOut * amountIn / (reserveIn + amountIn) = 1M * 10K / 1.01M = 9900
        val output = AmmMath.computeSwapOutput(10_000, reserve, reserve, feeBps = 0)!!
        val expected = reserve * 10_000L / (reserve + 10_000)
        assertEquals(expected, output, "Output should match no-fee constant product formula")
    }

    @Test
    fun `sequential trades degrade price progressively`() {
        var nexReserve = 1_000_000L
        var tokenReserve = 5_000_000L
        val outputs = mutableListOf<Long>()

        // 5 equal sells should get progressively worse output
        repeat(5) {
            val out = AmmMath.computeSellOutput(10_000, nexReserve, tokenReserve)!!
            outputs.add(out)
            val (newNex, newToken) = AmmMath.reservesAfterSell(10_000, nexReserve, tokenReserve)!!
            nexReserve = newNex
            tokenReserve = newToken
        }

        // Each output should be less than or equal to the previous
        for (i in 1 until outputs.size) {
            assertTrue(outputs[i] <= outputs[i - 1],
                "Trade $i output (${outputs[i]}) should be <= trade ${i-1} output (${outputs[i-1]})")
        }
    }
}
