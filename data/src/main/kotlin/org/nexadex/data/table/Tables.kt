package org.nexadex.data.table

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object TokensTable : Table("tokens") {
    val groupIdHex = varchar("group_id_hex", 64)
    val name = varchar("name", 255).nullable()
    val ticker = varchar("ticker", 10).nullable()
    val decimals = integer("decimals").default(0)
    val documentUrl = text("document_url").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(groupIdHex)
}

object PoolsTable : Table("pools") {
    val poolId = integer("pool_id").autoIncrement()
    val tokenGroupIdHex = varchar("token_group_id_hex", 64).references(TokensTable.groupIdHex)
    val lpGroupIdHex = varchar("lp_group_id_hex", 64)
    val initialLpSupply = long("initial_lp_supply").default(1_000_000_000L)
    val contractAddress = varchar("contract_address", 255)
    val contractBlob = binary("contract_blob")
    val status = varchar("status", 20).default("DEPLOYING")
    val nexReserve = long("nex_reserve").default(0L)
    val tokenReserve = long("token_reserve").default(0L)
    val deployTxId = varchar("deploy_tx_id", 64).nullable()
    val poolUtxoTxId = varchar("pool_utxo_txid", 64).nullable()
    val poolUtxoVout = integer("pool_utxo_vout").nullable()
    val contractVersion = varchar("contract_version", 10).default("v3")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(poolId)
}

object TradesTable : Table("trades") {
    val tradeId = integer("trade_id").autoIncrement()
    val poolId = integer("pool_id").references(PoolsTable.poolId)
    val direction = varchar("direction", 4)
    val amountIn = long("amount_in")
    val amountOut = long("amount_out")
    val price = double("price").nullable()
    val nexReserveAfter = long("nex_reserve_after")
    val tokenReserveAfter = long("token_reserve_after")
    val txId = varchar("tx_id", 64).nullable()
    val traderAddress = varchar("trader_address", 255).nullable()
    val status = varchar("status", 10).default("PENDING")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(tradeId)
}

object LpSharesTable : Table("lp_shares") {
    val shareId = integer("share_id").autoIncrement()
    val poolId = integer("pool_id").references(PoolsTable.poolId)
    val providerAddr = varchar("provider_addr", 255)
    val nexContributed = long("nex_contributed")
    val tokensContributed = long("tokens_contributed")
    val sharePct = double("share_pct").nullable()
    val depositTxId = varchar("deposit_tx_id", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(shareId)
}

object OhlcvTable : Table("ohlcv") {
    val poolId = integer("pool_id").references(PoolsTable.poolId)
    val interval = varchar("interval", 5)
    val openTime = timestampWithTimeZone("open_time")
    val open = double("open").nullable()
    val high = double("high").nullable()
    val low = double("low").nullable()
    val close = double("close").nullable()
    val volumeNex = long("volume_nex").default(0L)
    val volumeToken = long("volume_token").default(0L)
    val tradeCount = integer("trade_count").default(0)

    override val primaryKey = PrimaryKey(poolId, interval, openTime)
}
