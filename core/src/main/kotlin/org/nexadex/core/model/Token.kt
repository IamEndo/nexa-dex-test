package org.nexadex.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class Token(
    val groupIdHex: String,
    val name: String? = null,
    val ticker: String? = null,
    val decimals: Int = 0,
    val documentUrl: String? = null,
    val createdAt: Long = Instant.now().toEpochMilli(),
)
