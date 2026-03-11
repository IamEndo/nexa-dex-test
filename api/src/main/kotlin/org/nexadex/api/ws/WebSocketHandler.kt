package org.nexadex.api.ws

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nexadex.service.EventBus
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("WebSocketHandler")
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable
data class WsSubscribe(val subscribe: String)

@Serializable
data class WsMessage(val channel: String, val data: String)

private const val MAX_SUBSCRIPTIONS = 20

fun Application.webSocketRoutes(eventBus: EventBus) {
    routing {
        webSocket("/ws") {
            val subscriptions = mutableSetOf<String>()
            val jobs = mutableListOf<Job>()

            try {
                // Send welcome message
                send(Frame.Text(json.encodeToString(WsMessage("system", "connected"))))

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        try {
                            val msg = json.decodeFromString<WsSubscribe>(text)
                            val channel = msg.subscribe
                            if (subscriptions.size >= MAX_SUBSCRIPTIONS) {
                                send(Frame.Text(json.encodeToString(WsMessage("error", "Too many subscriptions (max $MAX_SUBSCRIPTIONS)"))))
                                continue
                            }
                            if (subscriptions.add(channel)) {
                                jobs += launchSubscription(channel, eventBus, this)
                                send(Frame.Text(json.encodeToString(WsMessage("system", "subscribed:$channel"))))
                            }
                        } catch (e: Exception) {
                            send(Frame.Text(json.encodeToString(WsMessage("error", "Invalid message"))))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug("WebSocket closed: {}", e.message)
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
    }
}

private fun CoroutineScope.launchSubscription(
    channel: String,
    eventBus: EventBus,
    session: WebSocketServerSession,
): Job = launch {
    when {
        // pool:1:trades — live trades for a pool
        channel.matches(Regex("pool:\\d+:trades")) -> {
            val poolId = channel.split(":")[1].toInt()
            eventBus.trades.filter { it.poolId == poolId }.collect { trade ->
                val data = json.encodeToString(
                    mapOf(
                        "tradeId" to trade.tradeId.toString(),
                        "direction" to trade.direction.name,
                        "amountIn" to trade.amountIn.toString(),
                        "amountOut" to trade.amountOut.toString(),
                        "price" to trade.price.toString(),
                        "txId" to (trade.txId ?: ""),
                    ),
                )
                session.send(Frame.Text(json.encodeToString(WsMessage(channel, data))))
            }
        }

        // pool:1:price — price updates after each trade
        channel.matches(Regex("pool:\\d+:price")) -> {
            val poolId = channel.split(":")[1].toInt()
            eventBus.priceUpdates.filter { it.poolId == poolId }.collect { update ->
                val data = json.encodeToString(
                    mapOf(
                        "spotPrice" to update.spotPrice.toString(),
                        "nexReserve" to update.nexReserve.toString(),
                        "tokenReserve" to update.tokenReserve.toString(),
                    ),
                )
                session.send(Frame.Text(json.encodeToString(WsMessage(channel, data))))
            }
        }

        // pool:1:candles:1m — candle updates
        channel.matches(Regex("pool:\\d+:candles:\\w+")) -> {
            val parts = channel.split(":")
            val poolId = parts[1].toInt()
            val interval = parts[3]
            eventBus.candleUpdates
                .filter { it.poolId == poolId && it.interval.label == interval }
                .collect { candle ->
                    val data = json.encodeToString(
                        mapOf(
                            "openTime" to candle.openTime.toString(),
                            "open" to candle.open.toString(),
                            "high" to candle.high.toString(),
                            "low" to candle.low.toString(),
                            "close" to candle.close.toString(),
                            "volumeNex" to candle.volumeNex.toString(),
                            "tradeCount" to candle.tradeCount.toString(),
                        ),
                    )
                    session.send(Frame.Text(json.encodeToString(WsMessage(channel, data))))
                }
        }

        // blocks — new block notifications
        channel == "blocks" -> {
            eventBus.blockNotifications.collect { block ->
                val data = json.encodeToString(
                    mapOf(
                        "height" to block.height.toString(),
                        "hash" to block.hash,
                    ),
                )
                session.send(Frame.Text(json.encodeToString(WsMessage(channel, data))))
            }
        }

        else -> {
            session.send(Frame.Text(json.encodeToString(WsMessage("error", "Unknown channel: $channel"))))
        }
    }
}
