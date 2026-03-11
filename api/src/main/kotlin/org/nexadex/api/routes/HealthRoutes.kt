package org.nexadex.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nexadex.api.dto.ApiResponse
import org.nexadex.api.dto.HealthResponse
import org.nexadex.core.model.PoolStatus
import org.nexadex.service.PoolService
import org.slf4j.LoggerFactory

private val json = Json { encodeDefaults = true }
private val healthLogger = LoggerFactory.getLogger("org.nexadex.api.HealthRoutes")

fun Application.healthRoutes(poolService: PoolService, startTime: Long) {
    routing {
        get("/api/v1/health") {
            val uptime = System.currentTimeMillis() - startTime

            val (allPools, dbConnected) = try {
                val pools = poolService.getAllPools()
                Pair(pools, true)
            } catch (e: Exception) {
                healthLogger.warn("DB health check failed: {}", e.message)
                Pair(emptyList(), false)
            }

            val activePools = allPools.count { it.status == PoolStatus.ACTIVE }

            val rostrumConnected = poolService.isRostrumConnected()

            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMb = runtime.maxMemory() / (1024 * 1024)

            val status = if (dbConnected && rostrumConnected && activePools > 0) "ok"
                else if (dbConnected) "degraded"
                else "unhealthy"

            val health = HealthResponse(
                status = status,
                version = "2.0.0",
                uptime = uptime,
                pools = allPools.size,
                activePools = activePools,
                connected = rostrumConnected,
                dbConnected = dbConnected,
                memoryUsedMb = usedMb,
                memoryMaxMb = maxMb,
            )

            val httpStatus = if (dbConnected) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respondText(
                json.encodeToString(ApiResponse.success(health)),
                ContentType.Application.Json,
                httpStatus,
            )
        }
    }
}
