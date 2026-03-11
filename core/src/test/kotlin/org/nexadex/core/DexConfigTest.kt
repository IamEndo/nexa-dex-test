package org.nexadex.core

import org.nexadex.core.config.*
import org.nexadex.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DexConfigTest {

    @Test
    fun `CorsConfig - parses comma-separated origins`() {
        val config = CorsConfig("http://localhost:3000, https://dex.nexa.org")
        val origins = config.originList()
        assertEquals(2, origins.size)
        assertEquals("http://localhost:3000", origins[0])
        assertEquals("https://dex.nexa.org", origins[1])
    }

    @Test
    fun `CorsConfig - empty string returns empty list`() {
        assertEquals(0, CorsConfig("").originList().size)
    }

    @Test
    fun `CandleInterval - fromLabel mapping`() {
        assertEquals(CandleInterval.M1, CandleInterval.fromLabel("1m"))
        assertEquals(CandleInterval.H1, CandleInterval.fromLabel("1h"))
        assertEquals(CandleInterval.D1, CandleInterval.fromLabel("1d"))
        assertEquals(null, CandleInterval.fromLabel("invalid"))
    }

    @Test
    fun `PoolStatus - all values`() {
        assertEquals(4, PoolStatus.entries.size)
        assertNotNull(PoolStatus.valueOf("DEPLOYING"))
        assertNotNull(PoolStatus.valueOf("ACTIVE"))
        assertNotNull(PoolStatus.valueOf("PAUSED"))
        assertNotNull(PoolStatus.valueOf("DRAINED"))
    }

    @Test
    fun `TradeDirection and TradeStatus - values`() {
        assertEquals(2, TradeDirection.entries.size)
        assertEquals(3, TradeStatus.entries.size)
    }

    @Test
    fun `ServerConfig - defaults`() {
        val config = ServerConfig()
        assertEquals("0.0.0.0", config.host)
        assertEquals(9090, config.port)
    }

    @Test
    fun `TradingConfig - defaults`() {
        val config = TradingConfig()
        assertEquals(500, config.maxSlippageBps)
        assertEquals(1500, config.maxPriceImpactBps)
        assertEquals(546L, config.minTradeNexSats)
        assertEquals(50, config.tradeQueueDepth)
        assertEquals(30_000L, config.tradeTimeoutMs)
    }

    @Test
    fun `Pool equality by poolId`() {
        val p1 = Pool(poolId = 1, tokenGroupIdHex = "aabb", lpGroupIdHex = "cc", contractAddress = "nexa:addr1")
        val p2 = Pool(poolId = 1, tokenGroupIdHex = "ddee", lpGroupIdHex = "ff", contractAddress = "nexa:addr2")
        assertEquals(p1, p2) // same poolId
    }
}
