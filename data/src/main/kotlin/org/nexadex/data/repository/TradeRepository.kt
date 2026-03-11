package org.nexadex.data.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.nexadex.core.model.Trade
import org.nexadex.core.model.TradeDirection
import org.nexadex.core.model.TradeStatus
import org.nexadex.data.table.TradesTable
import java.time.OffsetDateTime

class TradeRepository {

    fun insert(trade: Trade): Trade = transaction {
        val id = TradesTable.insert {
            it[poolId] = trade.poolId
            it[direction] = trade.direction.name
            it[amountIn] = trade.amountIn
            it[amountOut] = trade.amountOut
            it[price] = trade.price
            it[nexReserveAfter] = trade.nexReserveAfter
            it[tokenReserveAfter] = trade.tokenReserveAfter
            it[txId] = trade.txId
            it[traderAddress] = trade.traderAddress
            it[status] = trade.status.name
            it[createdAt] = OffsetDateTime.now()
        } get TradesTable.tradeId
        trade.copy(tradeId = id)
    }

    fun updateStatus(tradeId: Int, status: TradeStatus, txId: String? = null) = transaction {
        TradesTable.update({ TradesTable.tradeId eq tradeId }) {
            it[TradesTable.status] = status.name
            if (txId != null) it[TradesTable.txId] = txId
        }
    }

    fun findByPool(
        poolId: Int,
        limit: Int = 50,
        offset: Long = 0,
    ): List<Trade> = transaction {
        TradesTable.selectAll()
            .where { TradesTable.poolId eq poolId }
            .orderBy(TradesTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .offset(offset)
            .map { it.toTrade() }
    }

    fun findRecentByPool(poolId: Int, sinceMs: Long): List<Trade> = transaction {
        val since = OffsetDateTime.now().minusNanos(sinceMs * 1_000_000)
        TradesTable.selectAll()
            .where { (TradesTable.poolId eq poolId) and (TradesTable.createdAt greaterEq since) }
            .orderBy(TradesTable.createdAt, SortOrder.DESC)
            .map { it.toTrade() }
    }

    fun countRecentByPool(poolId: Int, sinceMs: Long): Long = transaction {
        val since = OffsetDateTime.now().minusNanos(sinceMs * 1_000_000)
        TradesTable.selectAll()
            .where {
                (TradesTable.poolId eq poolId) and
                    (TradesTable.createdAt greaterEq since) and
                    (TradesTable.status eq TradeStatus.CONFIRMED.name)
            }
            .count()
    }

    fun sumVolumeByPool(poolId: Int, sinceMs: Long): Pair<Long, Long> = transaction {
        val since = OffsetDateTime.now().minusNanos(sinceMs * 1_000_000)
        val trades = TradesTable.selectAll()
            .where {
                (TradesTable.poolId eq poolId) and
                    (TradesTable.createdAt greaterEq since) and
                    (TradesTable.status eq TradeStatus.CONFIRMED.name)
            }
            .toList()

        var volumeNex = 0L
        var volumeToken = 0L
        for (row in trades) {
            val dir = TradeDirection.valueOf(row[TradesTable.direction])
            when (dir) {
                TradeDirection.SELL -> {
                    volumeToken += row[TradesTable.amountIn]
                    volumeNex += row[TradesTable.amountOut]
                }
                TradeDirection.BUY -> {
                    volumeNex += row[TradesTable.amountIn]
                    volumeToken += row[TradesTable.amountOut]
                }
            }
        }
        Pair(volumeNex, volumeToken)
    }

    fun findPending(): List<Trade> = transaction {
        TradesTable.selectAll()
            .where { TradesTable.status eq TradeStatus.PENDING.name }
            .orderBy(TradesTable.createdAt, SortOrder.ASC)
            .map { it.toTrade() }
    }

    private fun ResultRow.toTrade() = Trade(
        tradeId = this[TradesTable.tradeId],
        poolId = this[TradesTable.poolId],
        direction = TradeDirection.valueOf(this[TradesTable.direction]),
        amountIn = this[TradesTable.amountIn],
        amountOut = this[TradesTable.amountOut],
        price = this[TradesTable.price] ?: 0.0,
        nexReserveAfter = this[TradesTable.nexReserveAfter],
        tokenReserveAfter = this[TradesTable.tokenReserveAfter],
        txId = this[TradesTable.txId],
        traderAddress = this[TradesTable.traderAddress],
        status = TradeStatus.valueOf(this[TradesTable.status]),
        createdAt = this[TradesTable.createdAt].toInstant().toEpochMilli(),
    )
}
