package org.nexadex.service

import org.nexadex.core.math.AmmMath
import org.nexadex.core.model.*
import org.nexadex.data.repository.OhlcvRepository
import org.nexadex.data.repository.PoolRepository
import org.nexadex.data.repository.TradeRepository
import org.slf4j.LoggerFactory

class AnalyticsService(
    private val poolRepo: PoolRepository,
    private val tradeRepo: TradeRepository,
    private val ohlcvRepo: OhlcvRepository,
    private val eventBus: EventBus,
) {
    private val logger = LoggerFactory.getLogger(AnalyticsService::class.java)

    companion object {
        private const val MS_24H = 86_400_000L
        private const val MS_7D = 604_800_000L
        private const val FEE_BPS = 30 // 0.3%
    }

    /**
     * Get stats for a pool.
     */
    fun getPoolStats(poolId: Int, token: Token?): PoolStats? {
        val pool = poolRepo.findById(poolId) ?: return null
        val spotPrice = AmmMath.spotPrice(pool.nexReserve, pool.tokenReserve) ?: 0.0
        val tvl = AmmMath.computeTvl(pool.nexReserve)

        val (vol24hNex, vol24hToken) = tradeRepo.sumVolumeByPool(poolId, MS_24H)
        val tradeCount24h = tradeRepo.countRecentByPool(poolId, MS_24H).toInt()

        // Price change: compare current spot price with 24h ago
        val priceChange24h = computePriceChange(poolId, MS_24H)

        // APY estimate: (24h fees × 365) / TVL × 100
        val dailyFees = vol24hNex.toDouble() * FEE_BPS / 10_000.0
        val apyPct = if (tvl > 0) dailyFees * 365.0 / tvl.toDouble() * 100.0 else 0.0

        return PoolStats(
            poolId = poolId,
            tokenGroupIdHex = pool.tokenGroupIdHex,
            tokenTicker = token?.ticker,
            nexReserve = pool.nexReserve,
            tokenReserve = pool.tokenReserve,
            spotPrice = spotPrice,
            tvlNexSats = tvl,
            volume24hNex = vol24hNex,
            volume24hToken = vol24hToken,
            tradeCount24h = tradeCount24h,
            priceChange24hPct = priceChange24h,
            apyEstimatePct = apyPct,
        )
    }

    /**
     * Record a trade in OHLCV candles across all intervals.
     */
    fun recordTradeInCandles(trade: Trade) {
        val spotPrice = if (trade.tokenReserveAfter > 0) {
            trade.nexReserveAfter.toDouble() / trade.tokenReserveAfter.toDouble()
        } else if (trade.price > 0.0) {
            trade.price
        } else {
            return // skip trades with no valid price
        }

        val (volNex, volToken) = when (trade.direction) {
            TradeDirection.SELL -> Pair(trade.amountOut, trade.amountIn)
            TradeDirection.BUY -> Pair(trade.amountIn, trade.amountOut)
        }

        for (interval in CandleInterval.entries) {
            val openTime = alignToInterval(trade.createdAt, interval)
            val existing = ohlcvRepo.findLatestCandle(trade.poolId, interval)

            val candle = if (existing != null && existing.openTime == openTime) {
                // Update existing candle
                existing.copy(
                    high = maxOf(existing.high, spotPrice),
                    low = minOf(existing.low, spotPrice),
                    close = spotPrice,
                    volumeNex = existing.volumeNex + volNex,
                    volumeToken = existing.volumeToken + volToken,
                    tradeCount = existing.tradeCount + 1,
                )
            } else {
                // New candle
                OhlcvCandle(
                    poolId = trade.poolId,
                    interval = interval,
                    openTime = openTime,
                    open = spotPrice,
                    high = spotPrice,
                    low = spotPrice,
                    close = spotPrice,
                    volumeNex = volNex,
                    volumeToken = volToken,
                    tradeCount = 1,
                )
            }

            ohlcvRepo.upsert(candle)
            eventBus.emitCandleUpdate(candle)
        }
    }

    /**
     * Backfill OHLCV candles from historical trades for all active pools.
     * Only runs if no candles exist yet.
     */
    fun backfillCandles() {
        try {
            logger.info("Checking candle backfill...")
            val pools = poolRepo.findAll()
            logger.info("Found {} total pools", pools.size)
            val active = pools.filter { it.status == PoolStatus.ACTIVE }
            logger.info("Found {} active pools for candle backfill", active.size)

            for (pool in active) {
                val existing = ohlcvRepo.findLatestCandle(pool.poolId, CandleInterval.H1)
                if (existing != null && existing.openTime > 0) {
                    logger.info("Pool {} already has valid candles (latest openTime={}), skipping", pool.poolId, existing.openTime)
                    continue
                }
                if (existing != null && existing.openTime == 0L) {
                    logger.info("Pool {} has bad candles (openTime=0), clearing and re-backfilling", pool.poolId)
                    ohlcvRepo.deleteByPool(pool.poolId)
                }

                val trades = tradeRepo.findAllByPoolAsc(pool.poolId)
                if (trades.isEmpty()) {
                    logger.info("Pool {} has no confirmed trades, skipping", pool.poolId)
                    continue
                }

                logger.info("Backfilling {} trades into candles for pool {}", trades.size, pool.poolId)
                if (trades.isNotEmpty()) {
                    val first = trades.first()
                    logger.info("First trade: id={}, createdAt={}, price={}, nexAfter={}, tokAfter={}",
                        first.tradeId, first.createdAt, first.price, first.nexReserveAfter, first.tokenReserveAfter)
                }
                for (trade in trades) {
                    recordTradeInCandles(trade)
                }
                logger.info("Backfill complete for pool {}", pool.poolId)
            }
        } catch (e: Exception) {
            logger.error("Candle backfill failed: {}", e.message, e)
        }
    }

    /**
     * Get OHLCV candles for a pool.
     */
    fun getCandles(
        poolId: Int,
        interval: CandleInterval,
        fromMs: Long,
        toMs: Long,
        limit: Int = 500,
    ): List<OhlcvCandle> = ohlcvRepo.findCandles(poolId, interval, fromMs, toMs, limit)

    private fun computePriceChange(poolId: Int, windowMs: Long): Double {
        val recentTrades = tradeRepo.findRecentByPool(poolId, windowMs)
        if (recentTrades.size < 2) return 0.0

        val newest = recentTrades.first()
        val oldest = recentTrades.last()

        val priceNow = if (newest.tokenReserveAfter > 0) {
            newest.nexReserveAfter.toDouble() / newest.tokenReserveAfter.toDouble()
        } else {
            return 0.0
        }

        val priceThen = if (oldest.tokenReserveAfter > 0) {
            oldest.nexReserveAfter.toDouble() / oldest.tokenReserveAfter.toDouble()
        } else {
            return 0.0
        }

        return if (priceThen > 0) (priceNow - priceThen) / priceThen * 100.0 else 0.0
    }

    private fun alignToInterval(timestampMs: Long, interval: CandleInterval): Long {
        return (timestampMs / interval.durationMs) * interval.durationMs
    }
}
