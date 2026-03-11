package org.nexadex.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.nexadex.api.dto.ApiResponse
import org.nexadex.service.AuthService
import org.nexadex.service.SessionManager
import org.nexadex.service.WsBrowserMessage
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.nexadex.api.AuthRoutes")
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable
data class ChallengeResponse(
    val cookie: String,
    val challenge: String,
    val nexIdUri: String,
)

@Serializable
data class VerifyRequest(
    val cookie: String,
    val address: String,
    val signature: String,
)

@Serializable
data class VerifyResponse(
    val sessionToken: String,
    val address: String,
)

@Serializable
data class SessionInfoResponse(
    val address: String?,
    val walletConnected: Boolean,
    val sessionId: String,
)

fun Application.authRoutes(sessionManager: SessionManager, authService: AuthService) {
    routing {

        /**
         * GET /_identity
         *
         * NexID protocol endpoint per spec (https://spec.nexa.org/nexid/).
         *
         * Two modes:
         * 1. Service discovery: No params → returns service info so Wally knows this is a valid NexID service
         * 2. Login callback: Wally sends op=login&addr=...&sig=...&cookie=... after signing the challenge
         */
        get("/_identity") {
            val op = call.request.queryParameters["op"]
            val addr = call.request.queryParameters["addr"]
            val sig = call.request.queryParameters["sig"]
            val cookie = call.request.queryParameters["cookie"]

            // Service discovery — Wally checks if this domain supports NexID
            if (op == null && addr == null) {
                val discovery = buildJsonObject {
                    put("nexid", true)
                    put("version", "1")
                    put("name", "MeowSwap")
                    putJsonArray("ops") { add("login") }
                }
                call.respondText(discovery.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                return@get
            }

            // Login callback from Wally
            if (op != "login" || addr.isNullOrBlank() || sig.isNullOrBlank() || cookie.isNullOrBlank()) {
                val err = buildJsonObject { put("error", "Missing required parameters: op, addr, sig, cookie") }
                call.respondText(err.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }

            val session = sessionManager.getSession(cookie)
            if (session == null) {
                val err = buildJsonObject { put("error", "Session expired or not found") }
                call.respondText(err.toString(), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }

            val result = authService.verifySignature(addr, sig, cookie)

            result.fold(
                onSuccess = { verified ->
                    session.nexAddress = addr

                    // Notify browsers that auth is complete
                    launch {
                        sessionManager.pushToBrowsers(session, WsBrowserMessage(
                            type = "wallet_connection",
                            data = """{"status":"authenticated","address":"$addr"}""",
                        ))
                    }

                    logger.info("NexID login via /_identity: addr={}, cookie={}", addr, cookie.take(6) + "...")

                    val ok = buildJsonObject { put("status", "ok"); put("address", addr) }
                    call.respondText(ok.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                },
                onFailure = { error ->
                    logger.warn("NexID login failed via /_identity: addr={}, error={}", addr, error.message)
                    val err = buildJsonObject { put("error", error.message ?: "Verification failed") }
                    call.respondText(err.toString(), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                },
            )
        }

        get("/api/v1/auth/challenge") {
            val session = sessionManager.createSession()
            val result = authService.createChallenge(session.sessionId)

            result.fold(
                onSuccess = { challenge ->
                    call.respondText(
                        json.encodeToString(ApiResponse.success(ChallengeResponse(
                            cookie = session.sessionId,
                            challenge = challenge.challenge,
                            nexIdUri = challenge.nexIdUri,
                        ))),
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                },
                onFailure = { error ->
                    call.respondText(
                        json.encodeToString(ApiResponse.error<Unit>("NEXID_ERROR", error.message ?: "Challenge creation failed")),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError,
                    )
                },
            )
        }

        post("/api/v1/auth/verify") {
            val req = try {
                json.decodeFromString<VerifyRequest>(call.receiveText())
            } catch (e: Exception) {
                return@post call.respondText(
                    json.encodeToString(ApiResponse.error<Unit>("INVALID_PARAM", "Invalid request body")),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }

            val session = sessionManager.getSession(req.cookie)
            if (session == null) {
                return@post call.respondText(
                    json.encodeToString(ApiResponse.error<Unit>("SESSION_NOT_FOUND", "Session expired or not found")),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
            }

            val result = authService.verifySignature(req.address, req.signature, req.cookie)

            result.fold(
                onSuccess = { verified ->
                    session.nexAddress = req.address

                    // Notify browsers
                    launch {
                        sessionManager.pushToBrowsers(session, WsBrowserMessage(
                            type = "wallet_connection",
                            data = """{"status":"authenticated","address":"${req.address}"}""",
                        ))
                    }

                    call.respondText(
                        json.encodeToString(ApiResponse.success(VerifyResponse(
                            sessionToken = verified.sessionToken,
                            address = req.address,
                        ))),
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                },
                onFailure = { error ->
                    call.respondText(
                        json.encodeToString(ApiResponse.error<Unit>("VERIFICATION_FAILED", error.message ?: "Verification failed")),
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized,
                    )
                },
            )
        }

        get("/api/v1/auth/session") {
            val cookie = call.request.queryParameters["cookie"]
                ?: call.request.cookies["dex_session"]
            if (cookie == null) {
                return@get call.respondText(
                    json.encodeToString(ApiResponse.error<Unit>("NO_SESSION", "No session cookie")),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
            }

            val session = sessionManager.getSession(cookie)
            if (session == null) {
                return@get call.respondText(
                    json.encodeToString(ApiResponse.error<Unit>("SESSION_NOT_FOUND", "Session expired or not found")),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
            }

            call.respondText(
                json.encodeToString(ApiResponse.success(SessionInfoResponse(
                    address = session.nexAddress,
                    walletConnected = session.walletConnected,
                    sessionId = session.sessionId,
                ))),
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }

        post("/api/v1/auth/logout") {
            val cookie = call.request.queryParameters["cookie"]
                ?: call.request.cookies["dex_session"]
            if (cookie != null) {
                sessionManager.destroySession(cookie)
                logger.info("Session logged out: {}", cookie)
            }

            call.respondText(
                json.encodeToString(ApiResponse.success(mapOf("status" to "logged_out"))),
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }
}
