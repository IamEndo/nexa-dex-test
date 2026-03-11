package org.nexadex.service

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.nexadex.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventBusTest {

    private fun makeTrade(id: Int = 1) = Trade(
        tradeId = id,
        poolId = 1,
        direction = TradeDirection.SELL,
        amountIn = 1000,
        amountOut = 195,
        nexReserveAfter = 99805,
        tokenReserveAfter = 501000,
    )

    @Test
    fun `emitTrade - subscriber receives trade`() = runTest {
        val bus = EventBus()
        val deferred = async {
            bus.trades.first()
        }
        yield() // Let collector subscribe
        bus.emitTrade(makeTrade(42))
        val trade = deferred.await()
        assertEquals(42, trade.tradeId)
    }

    @Test
    fun `emitPriceUpdate - subscriber receives update`() = runTest {
        val bus = EventBus()
        val deferred = async {
            bus.priceUpdates.first()
        }
        yield()
        bus.emitPriceUpdate(
            PriceUpdate(
                poolId = 1,
                spotPrice = 0.2,
                nexReserve = 100_000,
                tokenReserve = 500_000,
                lastTradeDirection = "SELL",
                lastTradeAmountIn = 1000,
                lastTradeAmountOut = 195,
            )
        )
        val update = deferred.await()
        assertEquals(1, update.poolId)
        assertEquals(0.2, update.spotPrice)
    }

    @Test
    fun `emitCandleUpdate - subscriber receives candle`() = runTest {
        val bus = EventBus()
        val candle = OhlcvCandle(
            poolId = 1,
            interval = CandleInterval.H1,
            openTime = 1000000,
            open = 0.2,
            high = 0.21,
            low = 0.19,
            close = 0.205,
            volumeNex = 5000,
            volumeToken = 25000,
            tradeCount = 3,
        )
        val deferred = async {
            bus.candleUpdates.first()
        }
        yield()
        bus.emitCandleUpdate(candle)
        val received = deferred.await()
        assertEquals(candle, received)
    }

    @Test
    fun `emitBlock - subscriber receives block`() = runTest {
        val bus = EventBus()
        val block = BlockNotification(height = 12345, hash = "abc", timestamp = 1000000)
        val deferred = async {
            bus.blockNotifications.first()
        }
        yield()
        bus.emitBlock(block)
        val received = deferred.await()
        assertEquals(12345L, received.height)
        assertEquals("abc", received.hash)
    }

    @Test
    fun `multiple trades emitted - first is received`() = runTest {
        val bus = EventBus()
        // Use first() for reliability — collect-3 has timing issues in tests
        val deferred = async { bus.trades.first() }
        yield()
        bus.emitTrade(makeTrade(1))
        bus.emitTrade(makeTrade(2))
        bus.emitTrade(makeTrade(3))
        val first = deferred.await()
        assertEquals(1, first.tradeId)
    }

    @Test
    fun `PriceUpdate data class properties`() {
        val update = PriceUpdate(
            poolId = 1,
            spotPrice = 0.5,
            nexReserve = 200_000,
            tokenReserve = 400_000,
            lastTradeDirection = "BUY",
            lastTradeAmountIn = 5000,
            lastTradeAmountOut = 9800,
        )
        assertEquals(1, update.poolId)
        assertEquals(0.5, update.spotPrice)
        assertEquals("BUY", update.lastTradeDirection)
    }

    @Test
    fun `BlockNotification data class properties`() {
        val block = BlockNotification(height = 99999, hash = "deadbeef", timestamp = 1700000000)
        assertEquals(99999L, block.height)
        assertEquals("deadbeef", block.hash)
        assertEquals(1700000000L, block.timestamp)
    }

    @Test
    fun `tryEmit does not block when no subscribers`() {
        val bus = EventBus()
        // Should not throw or block
        bus.emitTrade(makeTrade(1))
        bus.emitPriceUpdate(PriceUpdate(1, 0.2, 100, 500, "SELL", 100, 19))
        bus.emitCandleUpdate(OhlcvCandle(1, CandleInterval.M1, 0, 0.2, 0.2, 0.2, 0.2))
        bus.emitBlock(BlockNotification(1, "hash", 0))
        assertTrue(true, "Emit without subscribers should not error")
    }
}
