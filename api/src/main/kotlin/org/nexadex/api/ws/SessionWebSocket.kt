package org.nexadex.api.ws

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nexadex.service.SessionManager
import org.nexadex.service.WsBrowserMessage
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SessionWebSocket")
private val json = Json { encodeDefaults = true }

/**
 * WebSocket endpoint for browser ↔ server real-time communication.
 * Browser connects with session cookie for wallet status, tx results, etc.
 *
 * All sends are serialized via Mutex (SafeWebSocketSession pattern from NiftyArt)
 * to prevent concurrent send crashes.
 */
fun Application.sessionWebSocketRoutes(sessionManager: SessionManager) {
    routing {
        webSocket("/ws/session") {
            val cookie = call.request.queryParameters["cookie"]
            if (cookie.isNullOrBlank()) {
                send(Frame.Text(json.encodeToString(WsBrowserMessage("error", "Missing cookie parameter"))))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No cookie"))
                return@webSocket
            }

            val session = sessionManager.getSession(cookie)
            if (session == null) {
                send(Frame.Text(json.encodeToString(WsBrowserMessage("error", "Session expired or not found"))))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid session"))
                return@webSocket
            }

            logger.info("WS /ws/session connected: cookie={}", cookie.take(6))

            // Mutex to serialize all sends on this WebSocket — prevents concurrent send crashes
            // (NiftyArt's SafeWebSocketSession pattern)
            val sendMutex = Mutex()
            val wsSession = this

            suspend fun safeSend(text: String): Boolean {
                return sendMutex.withLock {
                    try {
                        if (wsSession.outgoing.isClosedForSend) return@withLock false
                        wsSession.send(Frame.Text(text))
                        true
                    } catch (e: Exception) {
                        logger.warn("WS safeSend failed: {} (session={})", e.message, cookie.take(6))
                        false
                    }
                }
            }

            // Register this WebSocket as a message handler (uses safeSend for thread safety)
            val removeHandler = sessionManager.registerBrowserHandler(session) { text ->
                logger.info("WS sending to browser: {} (session={})", text.take(80), cookie.take(6))
                safeSend(text) // returns false if WS is dead — pushToBrowsers will clean up
            }

            // Register destroy listener (non-blocking to avoid deadlock)
            val destroyListener = {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        wsSession.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Session destroyed"))
                    } catch (_: Exception) {}
                }
                Unit
            }
            synchronized(session.destroyListeners) { session.destroyListeners.add(destroyListener) }

            try {
                // Send initial state
                safeSend(json.encodeToString(WsBrowserMessage(
                    type = "connected",
                    data = buildString {
                        append("""{"sessionId":"${session.sessionId}"""")
                        session.nexAddress?.let { append(""","address":"$it"""") }
                        append(""","walletConnected":${session.walletConnected}""")
                        append("}")
                    },
                )))

                // Heartbeat — serialized through same Mutex, no concurrent send races
                val heartbeatJob = launch {
                    while (isActive) {
                        delay(15_000)
                        if (!safeSend(json.encodeToString(WsBrowserMessage("ping")))) {
                            break // WS dead
                        }
                    }
                }

                // Listen for incoming messages
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText().trim()
                        when {
                            text == "pong" || text.contains("\"pong\"") -> {}
                            text.contains("\"disconnect\"") -> break
                        }
                    }
                }

                heartbeatJob.cancel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug("Session WS closed: {} (session={})", e.message, cookie)
            } finally {
                removeHandler()
                synchronized(session.destroyListeners) { session.destroyListeners.remove(destroyListener) }
            }
        }
    }
}
