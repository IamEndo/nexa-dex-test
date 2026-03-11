package org.nexadex.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.nexadex.api.dto.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DtoTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // ── ApiResponse ──

    @Test
    fun `ApiResponse success factory`() {
        val resp = ApiResponse.success(42)
        assertTrue(resp.ok)
        assertEquals(42, resp.data)
        assertNull(resp.error)
    }

    @Test
    fun `ApiResponse error factory`() {
        val resp = ApiResponse.error<Unit>("NOT_FOUND", "Pool not found")
        assertFalse(resp.ok)
        assertNull(resp.data)
        assertNotNull(resp.error)
        assertEquals("NOT_FOUND", resp.error!!.type)
        assertEquals("Pool not found", resp.error!!.message)
        assertFalse(resp.error!!.retryable)
    }

    @Test
    fun `ApiResponse error with retryable`() {
        val resp = ApiResponse.error<Unit>("RATE_LIMITED", "Too many requests", retryable = true)
        assertTrue(resp.error!!.retryable)
    }

    @Test
    fun `ApiResponse success serialization`() {
        val resp = ApiResponse.success("hello")
        val str = json.encodeToString(resp)
        assertTrue(str.contains(""""ok":true"""))
        assertTrue(str.contains(""""data":"hello""""))
    }

    @Test
    fun `ApiResponse error serialization`() {
        val resp = ApiResponse.error<String>("ERR", "msg")
        val str = json.encodeToString(resp)
        assertTrue(str.contains(""""ok":false"""))
        assertTrue(str.contains(""""type":"ERR""""))
    }

    // ── Request DTOs ──

    @Test
    fun `CreatePoolRequest deserialization`() {
        val str = """{"tokenGroupIdHex":"aabbccdd","lpGroupIdHex":"11223344","initialNexSats":100000,"initialTokenAmount":500000,"mnemonic":"word1 word2 word3 word4 word5 word6 word7 word8 word9 word10 word11 word12"}"""
        val req = json.decodeFromString<CreatePoolRequest>(str)
        assertEquals("aabbccdd", req.tokenGroupIdHex)
        assertEquals(100000L, req.initialNexSats)
        assertEquals(500000L, req.initialTokenAmount)
        assertTrue(req.mnemonic.isNotBlank())
    }

    @Test
    fun `RegisterTokenRequest defaults`() {
        val str = """{"groupIdHex":"aabb"}"""
        val req = json.decodeFromString<RegisterTokenRequest>(str)
        assertEquals("aabb", req.groupIdHex)
        assertNull(req.name)
        assertNull(req.ticker)
        assertEquals(0, req.decimals)
        assertNull(req.documentUrl)
    }

    // ── Response DTOs ──

    @Test
    fun `PoolResponse serialization round trip`() {
        val resp = PoolResponse(
            poolId = 1,
            tokenGroupIdHex = "aabb",
            contractAddress = "nexa:contract",
            status = "ACTIVE",
            nexReserve = 100000,
            tokenReserve = 500000,
            spotPrice = 0.2,
            tvlNexSats = 200000,
        )
        val str = json.encodeToString(resp)
        val decoded = json.decodeFromString<PoolResponse>(str)
        assertEquals(resp, decoded)
    }

    @Test
    fun `TradeResponse serialization`() {
        val resp = TradeResponse(
            tradeId = 1,
            poolId = 1,
            direction = "SELL",
            amountIn = 1000,
            amountOut = 195,
            price = 0.195,
            txId = "abc123",
            status = "CONFIRMED",
            createdAt = 1700000000000,
        )
        val str = json.encodeToString(resp)
        assertTrue(str.contains("CONFIRMED"))
        val decoded = json.decodeFromString<TradeResponse>(str)
        assertEquals(resp, decoded)
    }

    @Test
    fun `StatsResponse serialization`() {
        val resp = StatsResponse(
            poolId = 1,
            nexReserve = 100000,
            tokenReserve = 500000,
            spotPrice = 0.2,
            tvlNexSats = 200000,
            volume24hNex = 50000,
            volume24hToken = 250000,
            tradeCount24h = 42,
            priceChange24hPct = 2.5,
            apyEstimatePct = 15.0,
        )
        val str = json.encodeToString(resp)
        val decoded = json.decodeFromString<StatsResponse>(str)
        assertEquals(resp, decoded)
    }

    @Test
    fun `TokenResponse serialization`() {
        val resp = TokenResponse(
            groupIdHex = "aabb",
            name = "Test Token",
            ticker = "TST",
            decimals = 8,
            documentUrl = null,
        )
        val str = json.encodeToString(resp)
        assertTrue(str.contains("TST"))
        val decoded = json.decodeFromString<TokenResponse>(str)
        assertEquals(resp, decoded)
    }

    @Test
    fun `HealthResponse serialization`() {
        val resp = HealthResponse(
            status = "ok",
            version = "2.0.0",
            uptime = 60000,
            pools = 5,
            activePools = 4,
            connected = true,
            dbConnected = true,
            memoryUsedMb = 128,
            memoryMaxMb = 512,
        )
        val str = json.encodeToString(resp)
        assertTrue(str.contains(""""status":"ok""""))
        assertTrue(str.contains(""""activePools":4"""))
        assertTrue(str.contains(""""dbConnected":true"""))
        val decoded = json.decodeFromString<HealthResponse>(str)
        assertEquals(resp, decoded)
    }

    @Test
    fun `HealthResponse defaults`() {
        val resp = HealthResponse(
            status = "ok",
            version = "2.0.0",
            uptime = 1000,
            pools = 3,
            connected = true,
        )
        assertEquals(3, resp.activePools)
        assertTrue(resp.dbConnected)
        assertEquals(0L, resp.memoryUsedMb)
    }

    @Test
    fun `CandleResponse serialization`() {
        val resp = CandleResponse(
            openTime = 1700000000000,
            open = 0.2,
            high = 0.21,
            low = 0.19,
            close = 0.205,
            volumeNex = 50000,
            volumeToken = 250000,
            tradeCount = 10,
        )
        val str = json.encodeToString(resp)
        val decoded = json.decodeFromString<CandleResponse>(str)
        assertEquals(resp, decoded)
    }
}
