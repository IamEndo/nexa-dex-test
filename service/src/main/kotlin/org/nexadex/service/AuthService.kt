package org.nexadex.service

import org.nexa.sdk.NexaSDK
import org.nexa.sdk.types.common.SdkResult
import org.slf4j.LoggerFactory

/**
 * NexID authentication service.
 * Wraps SDK identity operations for the API layer (which doesn't have SDK access).
 */
class AuthService(private val serverFqdn: String) {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)

    data class ChallengeResult(
        val challenge: String,
        val cookie: String,
        val nexIdUri: String,
    )

    data class VerifyResult(
        val sessionToken: String,
        val address: String,
    )

    /**
     * Create a NexID challenge and build the QR code URI.
     */
    fun createChallenge(sessionCookie: String): Result<ChallengeResult> {
        val challengeResult = NexaSDK.identity.createChallenge("login")
        val challenge = when (challengeResult) {
            is SdkResult.Success -> challengeResult.value
            is SdkResult.Failure -> {
                logger.error("Failed to create NexID challenge: {}", challengeResult.error)
                return Result.failure(RuntimeException("Failed to create challenge: ${challengeResult.error}"))
            }
        }

        val uriResult = NexaSDK.identity.buildNexIdUri(
            op = "login",
            challenge = challenge.challenge,
            cookie = sessionCookie,
            hdl = serverFqdn,
        )
        val nexIdUri = when (uriResult) {
            is SdkResult.Success -> uriResult.value
            is SdkResult.Failure -> {
                logger.error("Failed to build NexID URI: {}", uriResult.error)
                return Result.failure(RuntimeException("Failed to build NexID URI: ${uriResult.error}"))
            }
        }

        return Result.success(ChallengeResult(
            challenge = challenge.challenge,
            cookie = sessionCookie,
            nexIdUri = nexIdUri,
        ))
    }

    /**
     * Verify a NexID signature and create a session.
     */
    fun verifySignature(address: String, signature: String, cookie: String): Result<VerifyResult> {
        val result = NexaSDK.identity.verifyAndCreateSession(
            op = "login",
            address = address,
            signature = signature,
            cookie = cookie,
        )
        return when (result) {
            is SdkResult.Success -> {
                logger.info("NexID verified: address={}", address)
                Result.success(VerifyResult(
                    sessionToken = result.value.sessionToken,
                    address = address,
                ))
            }
            is SdkResult.Failure -> {
                logger.warn("NexID verification failed for {}: {}", address, result.error)
                Result.failure(RuntimeException("Verification failed: ${result.error}"))
            }
        }
    }

    /**
     * Cleanup expired SDK challenges and sessions.
     */
    fun cleanup() {
        try {
            NexaSDK.identity.cleanup()
        } catch (e: Exception) {
            logger.debug("NexID cleanup error: {}", e.message)
        }
    }
}
