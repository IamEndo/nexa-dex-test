package org.nexadex.data.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.nexadex.core.model.LpShare
import org.nexadex.data.table.LpSharesTable
import java.time.OffsetDateTime

class LpShareRepository {

    fun insert(share: LpShare): LpShare = transaction {
        val id = LpSharesTable.insert {
            it[poolId] = share.poolId
            it[providerAddr] = share.providerAddress
            it[nexContributed] = share.nexContributed
            it[tokensContributed] = share.tokensContributed
            it[sharePct] = share.sharePct
            it[depositTxId] = share.depositTxId
            it[createdAt] = OffsetDateTime.now()
        } get LpSharesTable.shareId
        share.copy(shareId = id)
    }

    fun findByPool(poolId: Int): List<LpShare> = transaction {
        LpSharesTable.selectAll()
            .where { LpSharesTable.poolId eq poolId }
            .orderBy(LpSharesTable.createdAt, SortOrder.DESC)
            .map { it.toShare() }
    }

    fun findByProvider(providerAddr: String): List<LpShare> = transaction {
        LpSharesTable.selectAll()
            .where { LpSharesTable.providerAddr eq providerAddr }
            .orderBy(LpSharesTable.createdAt, SortOrder.DESC)
            .map { it.toShare() }
    }

    fun updateContributions(shareId: Int, nexContributed: Long, tokensContributed: Long, sharePct: Double) = transaction {
        LpSharesTable.update({ LpSharesTable.shareId eq shareId }) {
            it[LpSharesTable.nexContributed] = nexContributed
            it[LpSharesTable.tokensContributed] = tokensContributed
            it[LpSharesTable.sharePct] = sharePct
        }
    }

    fun deleteByShareId(shareId: Int) = transaction {
        LpSharesTable.deleteWhere { LpSharesTable.shareId eq shareId }
    }

    private fun ResultRow.toShare() = LpShare(
        shareId = this[LpSharesTable.shareId],
        poolId = this[LpSharesTable.poolId],
        providerAddress = this[LpSharesTable.providerAddr],
        nexContributed = this[LpSharesTable.nexContributed],
        tokensContributed = this[LpSharesTable.tokensContributed],
        sharePct = this[LpSharesTable.sharePct] ?: 0.0,
        depositTxId = this[LpSharesTable.depositTxId],
        createdAt = this[LpSharesTable.createdAt].toInstant().toEpochMilli(),
    )
}
