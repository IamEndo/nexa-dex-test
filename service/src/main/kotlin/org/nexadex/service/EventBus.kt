package org.nexadex.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.nexadex.core.model.OhlcvCandle
import org.nexadex.core.model.Trade

/**
 * In-process event bus for real-time WebSocket feeds.
 * Uses SharedFlow for fan-out to multiple WebSocket subscribers.
 */
class EventBus {

    private val _trades = MutableSharedFlow<Trade>(extraBufferCapacity = 256)
    val trades: SharedFlow<Trade> = _trades.asSharedFlow()

    private val _priceUpdates = MutableSharedFlow<PriceUpdate>(extraBufferCapacity = 256)
    val priceUpdates: SharedFlow<PriceUpdate> = _priceUpdates.asSharedFlow()

    private val _candleUpdates = MutableSharedFlow<OhlcvCandle>(extraBufferCapacity = 256)
    val candleUpdates: SharedFlow<OhlcvCandle> = _candleUpdates.asSharedFlow()

    private val _blockNotifications = MutableSharedFlow<BlockNotification>(extraBufferCapacity = 64)
    val blockNotifications: SharedFlow<BlockNotification> = _blockNotifications.asSharedFlow()

    fun emitTrade(trade: Trade) {
        _trades.tryEmit(trade)
    }

    fun emitPriceUpdate(update: PriceUpdate) {
        _priceUpdates.tryEmit(update)
    }

    fun emitCandleUpdate(candle: OhlcvCandle) {
        _candleUpdates.tryEmit(candle)
    }

    fun emitBlock(block: BlockNotification) {
        _blockNotifications.tryEmit(block)
    }
}

data class PriceUpdate(
    val poolId: Int,
    val spotPrice: Double,
    val nexReserve: Long,
    val tokenReserve: Long,
    val lastTradeDirection: String,
    val lastTradeAmountIn: Long,
    val lastTradeAmountOut: Long,
)

data class BlockNotification(
    val height: Long,
    val hash: String,
    val timestamp: Long,
)
