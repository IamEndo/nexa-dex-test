package org.nexadex.core

import org.nexadex.core.validation.InputValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class InputValidatorTest {

    // ── Address validation ──

    @Test
    fun `validateAddress - valid nexa address`() {
        val addr = "nexa:nqtsq5g5sjkqk7wzd9wwh9423rr0tda7m027cmljgena8y"
        val result = InputValidator.validateAddress(addr)
        assertTrue(result.isSuccess)
        assertEquals(addr, result.getOrNull())
    }

    @Test
    fun `validateAddress - trims whitespace`() {
        val addr = "  nexa:nqtsq5g5sjkqk7wzd9wwh9423rr0tda7m027cmljgena8y  "
        val result = InputValidator.validateAddress(addr)
        assertTrue(result.isSuccess)
        assertEquals(addr.trim(), result.getOrNull())
    }

    @Test
    fun `validateAddress - empty string fails`() {
        val result = InputValidator.validateAddress("")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `validateAddress - missing prefix fails`() {
        val result = InputValidator.validateAddress("nqtsq5g5sjkqk7wzd9wwh9423rr0tda7m027cmljgena8y")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `validateAddress - too short fails`() {
        val result = InputValidator.validateAddress("nexa:abc")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `validateAddress - uppercase chars fail`() {
        val result = InputValidator.validateAddress("nexa:NQTSQ5G5SJKQK7WZD9WWH9423RR0TDA7M027CMLJGENA8Y")
        assertFalse(result.isSuccess)
    }

    // ── Group ID hex validation ──

    @Test
    fun `validateGroupIdHex - valid 64 hex chars`() {
        val hex = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
        val result = InputValidator.validateGroupIdHex(hex)
        assertTrue(result.isSuccess)
        assertEquals(hex, result.getOrNull())
    }

    @Test
    fun `validateGroupIdHex - uppercase normalized to lowercase`() {
        val hex = "A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4E5F6A1B2"
        val result = InputValidator.validateGroupIdHex(hex)
        assertTrue(result.isSuccess)
        assertEquals(hex.lowercase(), result.getOrNull())
    }

    @Test
    fun `validateGroupIdHex - too short fails`() {
        val result = InputValidator.validateGroupIdHex("aabb")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `validateGroupIdHex - too long fails`() {
        val result = InputValidator.validateGroupIdHex("a".repeat(65))
        assertFalse(result.isSuccess)
    }

    @Test
    fun `validateGroupIdHex - non-hex chars fail`() {
        val result = InputValidator.validateGroupIdHex("g".repeat(64))
        assertFalse(result.isSuccess)
    }

    @Test
    fun `validateGroupIdHex - trims whitespace`() {
        val hex = "  a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2  "
        val result = InputValidator.validateGroupIdHex(hex)
        assertTrue(result.isSuccess)
    }

    // ── Trade amount validation ──

    @Test
    fun `validateTradeAmount - valid positive amount`() {
        val result = InputValidator.validateTradeAmount(1000, "SELL")
        assertTrue(result.isSuccess)
        assertEquals(1000L, result.getOrNull())
    }

    @Test
    fun `validateTradeAmount - zero fails`() {
        val result = InputValidator.validateTradeAmount(0, "SELL")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `validateTradeAmount - negative fails`() {
        val result = InputValidator.validateTradeAmount(-100, "BUY")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `validateTradeAmount - exceeds max fails`() {
        val result = InputValidator.validateTradeAmount(100_000_000_000_000, "BUY")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `validateTradeAmount - just under max succeeds`() {
        val result = InputValidator.validateTradeAmount(10_000_000_000_000, "BUY")
        assertTrue(result.isSuccess)
    }

    // ── Slippage validation ──

    @Test
    fun `validateSlippageBps - valid 100 bps`() {
        val result = InputValidator.validateSlippageBps(100)
        assertTrue(result.isSuccess)
        assertEquals(100, result.getOrNull())
    }

    @Test
    fun `validateSlippageBps - zero allowed`() {
        val result = InputValidator.validateSlippageBps(0)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `validateSlippageBps - max 5000 allowed`() {
        val result = InputValidator.validateSlippageBps(5000)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `validateSlippageBps - negative fails`() {
        val result = InputValidator.validateSlippageBps(-1)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `validateSlippageBps - over 5000 fails`() {
        val result = InputValidator.validateSlippageBps(5001)
        assertFalse(result.isSuccess)
    }

    // ── Pool ID validation ──

    @Test
    fun `validatePoolId - valid positive`() {
        val result = InputValidator.validatePoolId(1)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
    }

    @Test
    fun `validatePoolId - zero fails`() {
        val result = InputValidator.validatePoolId(0)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `validatePoolId - negative fails`() {
        val result = InputValidator.validatePoolId(-5)
        assertFalse(result.isSuccess)
    }
}
