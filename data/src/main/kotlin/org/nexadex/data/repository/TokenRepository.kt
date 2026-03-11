package org.nexadex.data.repository

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.nexadex.core.model.Token
import org.nexadex.data.table.TokensTable
import java.time.OffsetDateTime

class TokenRepository {

    fun insert(token: Token): Token = transaction {
        TokensTable.insert {
            it[groupIdHex] = token.groupIdHex
            it[name] = token.name
            it[ticker] = token.ticker
            it[decimals] = token.decimals
            it[documentUrl] = token.documentUrl
            it[createdAt] = OffsetDateTime.now()
        }
        token
    }

    fun findByGroupId(groupIdHex: String): Token? = transaction {
        TokensTable.selectAll()
            .where { TokensTable.groupIdHex eq groupIdHex }
            .firstOrNull()
            ?.toToken()
    }

    fun findAll(): List<Token> = transaction {
        TokensTable.selectAll()
            .orderBy(TokensTable.createdAt, SortOrder.DESC)
            .map { it.toToken() }
    }

    fun exists(groupIdHex: String): Boolean = transaction {
        TokensTable.selectAll()
            .where { TokensTable.groupIdHex eq groupIdHex }
            .count() > 0
    }

    private fun ResultRow.toToken() = Token(
        groupIdHex = this[TokensTable.groupIdHex],
        name = this[TokensTable.name],
        ticker = this[TokensTable.ticker],
        decimals = this[TokensTable.decimals],
        documentUrl = this[TokensTable.documentUrl],
        createdAt = this[TokensTable.createdAt].toInstant().toEpochMilli(),
    )
}
