package org.nexadex.data.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.nexadex.core.model.Pool
import org.nexadex.core.model.PoolStatus
import org.nexadex.data.table.PoolsTable
import java.time.OffsetDateTime

class PoolRepository {

    fun insert(pool: Pool): Pool = transaction {
        val id = PoolsTable.insert {
            it[tokenGroupIdHex] = pool.tokenGroupIdHex
            it[lpGroupIdHex] = pool.lpGroupIdHex
            it[initialLpSupply] = pool.initialLpSupply
            it[contractAddress] = pool.contractAddress
            it[contractBlob] = pool.contractBlob
            it[status] = pool.status.name
            it[nexReserve] = pool.nexReserve
            it[tokenReserve] = pool.tokenReserve
            it[deployTxId] = pool.deployTxId
            it[poolUtxoTxId] = pool.poolUtxoTxId
            it[poolUtxoVout] = pool.poolUtxoVout
            it[contractVersion] = pool.contractVersion
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        } get PoolsTable.poolId
        pool.copy(poolId = id)
    }

    fun findById(poolId: Int): Pool? = transaction {
        PoolsTable.selectAll()
            .where { PoolsTable.poolId eq poolId }
            .firstOrNull()
            ?.toPool()
    }

    fun findByToken(tokenGroupIdHex: String): Pool? = transaction {
        PoolsTable.selectAll()
            .where { PoolsTable.tokenGroupIdHex eq tokenGroupIdHex }
            .firstOrNull()
            ?.toPool()
    }

    fun findAll(statusFilter: PoolStatus? = null): List<Pool> = transaction {
        val query = PoolsTable.selectAll()
        if (statusFilter != null) {
            query.andWhere { PoolsTable.status eq statusFilter.name }
        }
        query.orderBy(PoolsTable.poolId, SortOrder.ASC).map { it.toPool() }
    }

    fun findActive(): List<Pool> = findAll(PoolStatus.ACTIVE)

    fun updateReserves(poolId: Int, nexReserve: Long, tokenReserve: Long) = transaction {
        PoolsTable.update({ PoolsTable.poolId eq poolId }) {
            it[PoolsTable.nexReserve] = nexReserve
            it[PoolsTable.tokenReserve] = tokenReserve
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun updateStatus(poolId: Int, status: PoolStatus) = transaction {
        PoolsTable.update({ PoolsTable.poolId eq poolId }) {
            it[PoolsTable.status] = status.name
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun updateContractBlob(poolId: Int, blob: ByteArray) = transaction {
        PoolsTable.update({ PoolsTable.poolId eq poolId }) {
            it[contractBlob] = blob
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun updateDeployTxId(poolId: Int, txId: String) = transaction {
        PoolsTable.update({ PoolsTable.poolId eq poolId }) {
            it[deployTxId] = txId
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun updateStatusAndReserves(
        poolId: Int,
        status: PoolStatus,
        nexReserve: Long,
        tokenReserve: Long,
    ) = transaction {
        PoolsTable.update({ PoolsTable.poolId eq poolId }) {
            it[PoolsTable.status] = status.name
            it[PoolsTable.nexReserve] = nexReserve
            it[PoolsTable.tokenReserve] = tokenReserve
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun updatePoolUtxo(poolId: Int, txId: String, vout: Int) = transaction {
        PoolsTable.update({ PoolsTable.poolId eq poolId }) {
            it[poolUtxoTxId] = txId
            it[poolUtxoVout] = vout
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun updatePoolUtxoAndReserves(
        poolId: Int,
        txId: String,
        vout: Int,
        nexReserve: Long,
        tokenReserve: Long,
    ) = transaction {
        PoolsTable.update({ PoolsTable.poolId eq poolId }) {
            it[poolUtxoTxId] = txId
            it[poolUtxoVout] = vout
            it[PoolsTable.nexReserve] = nexReserve
            it[PoolsTable.tokenReserve] = tokenReserve
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun updateLpFields(poolId: Int, lpGroupIdHex: String, initialLpSupply: Long) = transaction {
        PoolsTable.update({ PoolsTable.poolId eq poolId }) {
            it[PoolsTable.lpGroupIdHex] = lpGroupIdHex
            it[PoolsTable.initialLpSupply] = initialLpSupply
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun findV2Pools(): List<Pool> = transaction {
        PoolsTable.selectAll()
            .where { PoolsTable.contractVersion eq "v2" }
            .map { it.toPool() }
    }

    fun findActiveV2Pools(): List<Pool> = transaction {
        PoolsTable.selectAll()
            .where { (PoolsTable.contractVersion inList listOf("v2", "v3")) and (PoolsTable.status eq PoolStatus.ACTIVE.name) }
            .map { it.toPool() }
    }

    private fun ResultRow.toPool() = Pool(
        poolId = this[PoolsTable.poolId],
        tokenGroupIdHex = this[PoolsTable.tokenGroupIdHex],
        lpGroupIdHex = this[PoolsTable.lpGroupIdHex],
        initialLpSupply = this[PoolsTable.initialLpSupply],
        contractAddress = this[PoolsTable.contractAddress],
        contractBlob = this[PoolsTable.contractBlob],
        status = PoolStatus.valueOf(this[PoolsTable.status]),
        nexReserve = this[PoolsTable.nexReserve],
        tokenReserve = this[PoolsTable.tokenReserve],
        deployTxId = this[PoolsTable.deployTxId],
        poolUtxoTxId = this[PoolsTable.poolUtxoTxId],
        poolUtxoVout = this[PoolsTable.poolUtxoVout],
        contractVersion = this[PoolsTable.contractVersion],
        createdAt = this[PoolsTable.createdAt].toInstant().toEpochMilli(),
        updatedAt = this[PoolsTable.updatedAt].toInstant().toEpochMilli(),
    )
}
