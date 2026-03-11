package org.nexadex.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.nexadex.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ModelTest {

    private val json = Json { encodeDefaults = true }

    // ── Pool ──

    @Test
    fun `Pool - default values`() {
        val pool = Pool(tokenGroupIdHex = "aabb", lpGroupIdHex = "cc", contractAddress = "nexa:addr")
        assertEquals(0, pool.poolId)
        assertEquals(PoolStatus.DEPLOYING, pool.status)
        assertEquals(0L, pool.nexReserve)
        assertEquals(0L, pool.tokenReserve)
        assertNull(pool.deployTxId)
    }

    @Test
    fun `Pool - equality by poolId only`() {
        val a = Pool(poolId = 1, tokenGroupIdHex = "aa", lpGroupIdHex = "bb", contractAddress = "addr1")
        val b = Pool(poolId = 1, tokenGroupIdHex = "cc", lpGroupIdHex = "dd", contractAddress = "addr2")
        val c = Pool(poolId = 2, tokenGroupIdHex = "aa", lpGroupIdHex = "bb", contractAddress = "addr1")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `Pool - serialization round trip`() {
        val pool = Pool(
            poolId = 42,
            tokenGroupIdHex = "aabbccdd",
            lpGroupIdHex = "eeff0011",
            contractAddress = "nexa:test",
            status = PoolStatus.ACTIVE,
            nexReserve = 100_000L,
            tokenReserve = 500_000L,
            deployTxId = "txid123",
        )
        val jsonStr = json.encodeToString(pool)
        val decoded = json.decodeFromString<Pool>(jsonStr)
        assertEquals(pool.poolId, decoded.poolId)
        assertEquals(pool.tokenGroupIdHex, decoded.tokenGroupIdHex)
        assertEquals(pool.status, decoded.status)
        assertEquals(pool.nexReserve, decoded.nexReserve)
    }

    @Test
    fun `PoolStatus - all values`() {
        assertEquals(4, PoolStatus.entries.size)
        assertEquals(PoolStatus.DEPLOYING, PoolStatus.valueOf("DEPLOYING"))
        assertEquals(PoolStatus.ACTIVE, PoolStatus.valueOf("ACTIVE"))
        assertEquals(PoolStatus.PAUSED, PoolStatus.valueOf("PAUSED"))
        assertEquals(PoolStatus.DRAINED, PoolStatus.valueOf("DRAINED"))
    }

    // ── Trade ──

    @Test
    fun `Trade - default values`() {
        val trade = Trade(
            poolId = 1,
            direction = TradeDirection.SELL,
            amountIn = 1000,
            amountOut = 190,
            nexReserveAfter = 99810,
            tokenReserveAfter = 501000,
        )
        assertEquals(0, trade.tradeId)
        assertEquals(TradeStatus.PENDING, trade.status)
        assertNull(trade.txId)
        assertNull(trade.traderAddress)
    }

    @Test
    fun `Trade - serialization`() {
        val trade = Trade(
            tradeId = 5,
            poolId = 1,
            direction = TradeDirection.BUY,
            amountIn = 5000,
            amountOut = 24000,
            price = 0.2083,
            nexReserveAfter = 105000,
            tokenReserveAfter = 476000,
            txId = "abc123",
            status = TradeStatus.CONFIRMED,
        )
        val jsonStr = json.encodeToString(trade)
        val decoded = json.decodeFromString<Trade>(jsonStr)
        assertEquals(trade.tradeId, decoded.tradeId)
        assertEquals(trade.direction, decoded.direction)
        assertEquals(trade.status, decoded.status)
    }

    @Test
    fun `TradeDirection - all values`() {
        assertEquals(2, TradeDirection.entries.size)
        assertEquals(TradeDirection.BUY, TradeDirection.valueOf("BUY"))
        assertEquals(TradeDirection.SELL, TradeDirection.valueOf("SELL"))
    }

    @Test
    fun `TradeStatus - all values`() {
        assertEquals(3, TradeStatus.entries.size)
        assertEquals(TradeStatus.PENDING, TradeStatus.valueOf("PENDING"))
        assertEquals(TradeStatus.CONFIRMED, TradeStatus.valueOf("CONFIRMED"))
        assertEquals(TradeStatus.FAILED, TradeStatus.valueOf("FAILED"))
    }

    // ── Token ──

    @Test
    fun `Token - default decimals`() {
        val token = Token(groupIdHex = "aabb")
        assertEquals(0, token.decimals)
        assertNull(token.name)
        assertNull(token.ticker)
        assertNull(token.documentUrl)
    }

    @Test
    fun `Token - serialization`() {
        val token = Token(
            groupIdHex = "aabbccdd",
            name = "Test Token",
            ticker = "TST",
            decimals = 8,
            documentUrl = "https://example.com",
        )
        val jsonStr = json.encodeToString(token)
        assertTrue(jsonStr.contains("TST"))
        val decoded = json.decodeFromString<Token>(jsonStr)
        assertEquals(token.ticker, decoded.ticker)
        assertEquals(token.decimals, decoded.decimals)
    }

    // ── LpShare ──

    @Test
    fun `LpShare - defaults`() {
        val share = LpShare(
            poolId = 1,
            providerAddress = "nexa:addr",
            nexContributed = 10000,
            tokensContributed = 50000,
        )
        assertEquals(0, share.shareId)
        assertEquals(0.0, share.sharePct)
        assertNull(share.depositTxId)
    }

    // ── OhlcvCandle ──

    @Test
    fun `OhlcvCandle - defaults`() {
        val candle = OhlcvCandle(
            poolId = 1,
            interval = CandleInterval.H1,
            openTime = 1000000L,
            open = 0.2,
            high = 0.25,
            low = 0.18,
            close = 0.22,
        )
        assertEquals(0L, candle.volumeNex)
        assertEquals(0L, candle.volumeToken)
        assertEquals(0, candle.tradeCount)
    }

    @Test
    fun `CandleInterval - durations`() {
        assertEquals(60_000L, CandleInterval.M1.durationMs)
        assertEquals(300_000L, CandleInterval.M5.durationMs)
        assertEquals(900_000L, CandleInterval.M15.durationMs)
        assertEquals(3_600_000L, CandleInterval.H1.durationMs)
        assertEquals(14_400_000L, CandleInterval.H4.durationMs)
        assertEquals(86_400_000L, CandleInterval.D1.durationMs)
    }

    @Test
    fun `CandleInterval - fromLabel all intervals`() {
        assertEquals(CandleInterval.M1, CandleInterval.fromLabel("1m"))
        assertEquals(CandleInterval.M5, CandleInterval.fromLabel("5m"))
        assertEquals(CandleInterval.M15, CandleInterval.fromLabel("15m"))
        assertEquals(CandleInterval.H1, CandleInterval.fromLabel("1h"))
        assertEquals(CandleInterval.H4, CandleInterval.fromLabel("4h"))
        assertEquals(CandleInterval.D1, CandleInterval.fromLabel("1d"))
        assertNull(CandleInterval.fromLabel("2h"))
        assertNull(CandleInterval.fromLabel(""))
    }

    // ── PriceQuote ──

    @Test
    fun `PriceQuote - serialization`() {
        val quote = PriceQuote(
            poolId = 1,
            direction = TradeDirection.SELL,
            amountIn = 1000L,
            amountOut = 195L,
            price = 0.195,
            priceImpactBps = 50,
            minimumReceived = 193L,
            nexReserveBefore = 100_000L,
            tokenReserveBefore = 500_000L,
        )
        val jsonStr = json.encodeToString(quote)
        val decoded = json.decodeFromString<PriceQuote>(jsonStr)
        assertEquals(quote.poolId, decoded.poolId)
        assertEquals(quote.direction, decoded.direction)
        assertEquals(quote.amountOut, decoded.amountOut)
        assertEquals(quote.priceImpactBps, decoded.priceImpactBps)
    }

    // ── PoolStats ──

    @Test
    fun `PoolStats - defaults`() {
        val stats = PoolStats(
            poolId = 1,
            tokenGroupIdHex = "aabb",
            nexReserve = 100_000L,
            tokenReserve = 500_000L,
            spotPrice = 0.2,
            tvlNexSats = 200_000L,
        )
        assertEquals(0L, stats.volume24hNex)
        assertEquals(0, stats.tradeCount24h)
        assertEquals(0.0, stats.priceChange24hPct)
        assertEquals(0.0, stats.apyEstimatePct)
    }
}
