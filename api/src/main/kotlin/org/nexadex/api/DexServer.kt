package org.nexadex.api

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nexadex.api.dto.ApiResponse
import org.nexadex.api.middleware.RateLimiter
import org.nexadex.api.routes.*
import org.nexadex.api.ws.sessionWebSocketRoutes
import org.nexadex.api.ws.webSocketRoutes
import org.nexadex.core.config.DexConfig
import org.nexadex.core.error.DexError
import org.nexadex.data.repository.PoolRepository
import org.nexadex.data.repository.TokenRepository
import org.nexadex.data.repository.TradeRepository
import org.nexadex.service.AnalyticsService
import org.nexadex.service.EventBus
import org.nexadex.service.LiquidityService
import org.nexadex.service.AuthService
import org.nexadex.service.PoolService
import org.nexadex.service.SessionManager
import org.nexadex.service.SwapServiceV2
import org.slf4j.LoggerFactory
import java.time.Duration

private val logger = LoggerFactory.getLogger("org.nexadex.api.DexServer")
private val json = Json { encodeDefaults = true }

fun Application.dexModule(
    config: DexConfig,
    poolService: PoolService,
    swapServiceV2: SwapServiceV2,
    liquidityService: LiquidityService,
    analyticsService: AnalyticsService,
    eventBus: EventBus,
    poolRepo: PoolRepository,
    tokenRepo: TokenRepository,
    tradeRepo: TradeRepository,
    sessionManager: SessionManager,
    authService: AuthService,
    startTime: Long,
) {
    // Content negotiation
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true; ignoreUnknownKeys = true })
    }

    // WebSocket support — match NiftyArt: 15s ping, 15s timeout, unlimited frame size
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    // CORS — permissive like NiftyArt (anyHost + credentials + OPTIONS)
    install(CORS) {
        anyHost()
        allowCredentials = true
        allowNonSimpleContentTypes = true
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
    }

    // Error handling
    install(StatusPages) {
        exception<DexError> { call, cause ->
            val status = when (cause) {
                is DexError.RateLimited -> HttpStatusCode.TooManyRequests
                is DexError.PoolNotFound, is DexError.TokenNotFound -> HttpStatusCode.NotFound
                else -> HttpStatusCode.BadRequest
            }
            call.respondText(
                json.encodeToString(ApiResponse.error<Unit>(cause.type, cause.message, cause.retryable)),
                ContentType.Application.Json,
                status,
            )
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception on ${call.request.local.uri}", cause)
            call.respondText(
                json.encodeToString(ApiResponse.error<Unit>("INTERNAL_ERROR", "Internal server error")),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Rate limiters
    val swapRateLimiter = RateLimiter(
        maxTokens = config.security.swapRateLimitPerMinute,
        refillPerSecond = config.security.swapRateLimitPerMinute / 60.0,
    )

    // Routes
    healthRoutes(poolService, startTime)
    tokenRoutes(tokenRepo)
    poolRoutes(poolService, analyticsService, tradeRepo, tokenRepo)
    swapRoutesV2(swapServiceV2, swapRateLimiter)
    liquidityRoutes(liquidityService, poolRepo, swapRateLimiter)
    webSocketRoutes(eventBus)

    // Wally wallet integration routes
    authRoutes(sessionManager, authService)
    walletPollRoutes(sessionManager, swapServiceV2)
    sessionWebSocketRoutes(sessionManager)

    // TDPP routes (Wally-signed transactions)
    val serverFqdn = System.getenv("NEXADEX_SERVER_FQDN") ?: "localhost:9090"
    tdppRoutes(swapServiceV2, sessionManager, serverFqdn)
}
