package org.nexadex.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nexadex.api.dto.ApiResponse
import org.nexadex.api.dto.RegisterTokenRequest
import org.nexadex.api.dto.TokenResponse
import org.nexadex.core.model.Token
import org.nexadex.core.validation.InputValidator
import org.nexadex.data.repository.TokenRepository

private val json = Json { encodeDefaults = true }

fun Application.tokenRoutes(tokenRepo: TokenRepository) {
    routing {
        get("/api/v1/tokens") {
            val tokens = tokenRepo.findAll().map { t ->
                TokenResponse(
                    groupIdHex = t.groupIdHex,
                    name = t.name,
                    ticker = t.ticker,
                    decimals = t.decimals,
                    documentUrl = t.documentUrl,
                )
            }
            call.respondText(
                json.encodeToString(ApiResponse.success(tokens)),
                ContentType.Application.Json,
            )
        }

        post("/api/v1/tokens") {
            val req = try {
                json.decodeFromString<RegisterTokenRequest>(call.receiveText())
            } catch (e: Exception) {
                return@post call.respondError("INVALID_BODY", "Invalid or malformed JSON body")
            }

            val validatedGroupId = InputValidator.validateGroupIdHex(req.groupIdHex).getOrElse { error ->
                return@post call.respondError(error.type, error.message)
            }

            // Validate token metadata lengths
            if ((req.name?.length ?: 0) > 255) {
                return@post call.respondError("INVALID_PARAM", "Token name too long (max 255)")
            }
            if ((req.ticker?.length ?: 0) > 10) {
                return@post call.respondError("INVALID_PARAM", "Ticker too long (max 10)")
            }
            if ((req.documentUrl?.length ?: 0) > 2048) {
                return@post call.respondError("INVALID_PARAM", "Document URL too long (max 2048)")
            }
            if (req.decimals < 0 || req.decimals > 18) {
                return@post call.respondError("INVALID_PARAM", "Decimals must be 0-18")
            }

            if (tokenRepo.exists(validatedGroupId)) {
                return@post call.respondError("TOKEN_ALREADY_REGISTERED", "Token already registered")
            }

            val token = tokenRepo.insert(
                Token(
                    groupIdHex = validatedGroupId,
                    name = req.name,
                    ticker = req.ticker,
                    decimals = req.decimals,
                    documentUrl = req.documentUrl,
                ),
            )

            val resp = TokenResponse(
                groupIdHex = token.groupIdHex,
                name = token.name,
                ticker = token.ticker,
                decimals = token.decimals,
                documentUrl = token.documentUrl,
            )
            call.respondText(
                json.encodeToString(ApiResponse.success(resp)),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        }

        get("/api/v1/tokens/{groupIdHex}") {
            val rawGroupId = call.parameters["groupIdHex"]
                ?: return@get call.respondError("INVALID_PARAM", "Missing group ID")
            if (rawGroupId.length > 64 || !rawGroupId.matches(Regex("^[a-fA-F0-9]+$"))) {
                return@get call.respondError("INVALID_PARAM", "Invalid group ID format")
            }
            val groupIdHex = rawGroupId.lowercase()

            val token = tokenRepo.findByGroupId(groupIdHex)
                ?: return@get call.respondError("TOKEN_NOT_FOUND", "Token not found", HttpStatusCode.NotFound)

            val resp = TokenResponse(
                groupIdHex = token.groupIdHex,
                name = token.name,
                ticker = token.ticker,
                decimals = token.decimals,
                documentUrl = token.documentUrl,
            )
            call.respondText(
                json.encodeToString(ApiResponse.success(resp)),
                ContentType.Application.Json,
            )
        }
    }
}
