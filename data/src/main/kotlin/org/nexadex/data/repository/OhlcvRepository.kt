package org.nexadex.data.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.nexadex.core.model.CandleInterval
import org.nexadex.core.model.OhlcvCandle
import org.nexadex.data.table.OhlcvTable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class OhlcvRepository {

    fun upsert(candle: OhlcvCandle) = transaction {
        val openTimeOdt = OffsetDateTime.ofInstant(
            Instant.ofEpochMilli(candle.openTime), ZoneOffset.UTC,
        )
        val existing = OhlcvTable.selectAll()
            .where {
                (OhlcvTable.poolId eq candle.poolId) and
                    (OhlcvTable.interval eq candle.interval.label) and
                    (OhlcvTable.openTime eq openTimeOdt)
            }
            .firstOrNull()

        if (existing != null) {
            OhlcvTable.update({
                (OhlcvTable.poolId eq candle.poolId) and
                    (OhlcvTable.interval eq candle.interval.label) and
                    (OhlcvTable.openTime eq openTimeOdt)
            }) {
                it[high] = candle.high
                it[low] = candle.low
                it[close] = candle.close
                it[volumeNex] = candle.volumeNex
                it[volumeToken] = candle.volumeToken
                it[tradeCount] = candle.tradeCount
            }
        } else {
            OhlcvTable.insert {
                it[poolId] = candle.poolId
                it[interval] = candle.interval.label
                it[openTime] = openTimeOdt
                it[open] = candle.open
                it[high] = candle.high
                it[low] = candle.low
                it[close] = candle.close
                it[volumeNex] = candle.volumeNex
                it[volumeToken] = candle.volumeToken
                it[tradeCount] = candle.tradeCount
            }
        }
    }

    fun findCandles(
        poolId: Int,
        interval: CandleInterval,
        fromMs: Long,
        toMs: Long,
        limit: Int = 500,
    ): List<OhlcvCandle> = transaction {
        val from = OffsetDateTime.ofInstant(Instant.ofEpochMilli(fromMs), ZoneOffset.UTC)
        val to = OffsetDateTime.ofInstant(Instant.ofEpochMilli(toMs), ZoneOffset.UTC)

        OhlcvTable.selectAll()
            .where {
                (OhlcvTable.poolId eq poolId) and
                    (OhlcvTable.interval eq interval.label) and
                    (OhlcvTable.openTime greaterEq from) and
                    (OhlcvTable.openTime lessEq to)
            }
            .orderBy(OhlcvTable.openTime, SortOrder.ASC)
            .limit(limit)
            .map { it.toCandle() }
    }

    fun findLatestCandle(poolId: Int, interval: CandleInterval): OhlcvCandle? = transaction {
        OhlcvTable.selectAll()
            .where {
                (OhlcvTable.poolId eq poolId) and
                    (OhlcvTable.interval eq interval.label)
            }
            .orderBy(OhlcvTable.openTime, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toCandle()
    }

    private fun ResultRow.toCandle() = OhlcvCandle(
        poolId = this[OhlcvTable.poolId],
        interval = CandleInterval.fromLabel(this[OhlcvTable.interval]) ?: CandleInterval.M1,
        openTime = this[OhlcvTable.openTime].toInstant().toEpochMilli(),
        open = this[OhlcvTable.open] ?: 0.0,
        high = this[OhlcvTable.high] ?: 0.0,
        low = this[OhlcvTable.low] ?: 0.0,
        close = this[OhlcvTable.close] ?: 0.0,
        volumeNex = this[OhlcvTable.volumeNex],
        volumeToken = this[OhlcvTable.volumeToken],
        tradeCount = this[OhlcvTable.tradeCount],
    )
}
