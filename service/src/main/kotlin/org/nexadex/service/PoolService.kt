package org.nexadex.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nexadex.core.error.DexError
import org.nexadex.core.error.DexResult
import org.nexadex.core.math.AmmMath
import org.nexadex.core.model.*
import org.nexadex.data.repository.LpShareRepository
import org.nexadex.data.repository.PoolRepository
import org.nexadex.data.repository.TokenRepository
import kotlinx.coroutines.delay
import org.nexa.sdk.NexaSDK
import org.nexa.sdk.types.primitives.NexaAmount
import org.nexa.sdk.types.primitives.TokenId
import org.nexa.sdk.types.common.SdkResult
import org.nexa.sdk.types.contract.ContractInstance
import org.nexa.sdk.types.token.TokenMetadata
import org.nexa.sdk.types.wallet.Mnemonic
import org.nexa.sdk.types.wallet.Network
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class PoolService(
    private val poolRepo: PoolRepository,
    private val tokenRepo: TokenRepository,
    private val lpShareRepo: LpShareRepository,
) {
    private val logger = LoggerFactory.getLogger(PoolService::class.java)
    private val sdk get() = NexaSDK

    // Per-pool mutexes for sequential UTXO operations
    private val poolMutexes = ConcurrentHashMap<Int, Mutex>()

    // In-memory cache of deserialized ContractInstances
    private val instanceCache = ConcurrentHashMap<Int, ContractInstance>()

    fun getMutex(poolId: Int): Mutex = poolMutexes.computeIfAbsent(poolId) { Mutex() }

    fun getPool(poolId: Int): DexResult<Pool> {
        val pool = poolRepo.findById(poolId)
            ?: return DexResult.failure(DexError.PoolNotFound(poolId))
        return DexResult.success(pool)
    }

    fun getActivePools(): List<Pool> = poolRepo.findActive()

    fun getAllPools(): List<Pool> = poolRepo.findAll()

    fun isRostrumConnected(): Boolean = try {
        sdk.connection.connectionStatus().isReady
    } catch (e: Exception) {
        logger.warn("Rostrum connection check failed: {}", e.message)
        false
    }

    fun registerPool(pool: Pool): Pool {
        logger.info("Registering pre-deployed pool: token={}, contract={}", pool.tokenGroupIdHex, pool.contractAddress)
        return poolRepo.insert(pool)
    }

    fun getContractInstance(pool: Pool): ContractInstance {
        return instanceCache.getOrPut(pool.poolId) {
            if (pool.contractBlob.isNotEmpty()) {
                ContractInstanceSerializer.deserialize(pool.contractBlob)
            } else {
                // Re-instantiate from group IDs (for pools registered without blob)
                val tradeGroupIdBytes = hexToBytes(pool.tokenGroupIdHex)
                val lpGroupIdBytes = hexToBytes(pool.lpGroupIdHex)
                val instance = when (val r = sdk.contract.instantiateAmmDex(tradeGroupIdBytes, lpGroupIdBytes, pool.initialLpSupply)) {
                    is SdkResult.Success -> r.value
                    is SdkResult.Failure -> throw IllegalStateException("Failed to instantiate contract for pool ${pool.poolId}: ${r.error}")
                }
                // Persist the blob so we don't re-instantiate next time
                val blob = ContractInstanceSerializer.serialize(instance)
                poolRepo.updateContractBlob(pool.poolId, blob)
                instance
            }
        }
    }

    fun updateReserves(poolId: Int, nexReserve: Long, tokenReserve: Long) {
        poolRepo.updateReserves(poolId, nexReserve, tokenReserve)
    }

    /**
     * Create a fully permissionless pool using AmmDex contract with LP tokens.
     *
     * If lpGroupIdHex is empty, automatically creates an LP token group and mints
     * [initialLpSupply] tokens before deploying the pool. This takes ~60s due to
     * UTXO propagation delays.
     */
    suspend fun createPool(
        tokenGroupIdHex: String,
        lpGroupIdHex: String,
        initialLpSupply: Long,
        initialNexSats: Long,
        initialTokenAmount: Long,
        creatorMnemonic: String,
    ): DexResult<Pool> {
        if (!tokenRepo.exists(tokenGroupIdHex)) {
            return DexResult.failure(DexError.TokenNotFound(tokenGroupIdHex))
        }

        if (initialLpSupply < 2000L) {
            return DexResult.failure(DexError.InvalidOperation("initialLpSupply must be >= 2000 (1000 locked as MINIMUM_LIQUIDITY)"))
        }

        // Create wallet from mnemonic
        val mnemonic = when (val r = Mnemonic.parse(creatorMnemonic)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> return DexResult.failure(DexError.InvalidOperation("Invalid mnemonic"))
        }
        val wallet = when (val r = sdk.wallet.createFromMnemonic(mnemonic, Network.MAINNET)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> return DexResult.failure(DexError.InternalError("Failed to create wallet: ${r.error}"))
        }

        return try {
            // Resolve LP group: auto-create if not provided
            val resolvedLpGroupIdHex = if (lpGroupIdHex.isNotEmpty()) {
                lpGroupIdHex
            } else {
                logger.info("Auto-creating LP token group for trade token {}", tokenGroupIdHex)
                autoCreateLpGroup(wallet, tokenGroupIdHex, initialLpSupply)
                    ?: return DexResult.failure(DexError.PoolDeployFailed("Failed to auto-create LP token group"))
            }

            val tradeGroupIdBytes = hexToBytes(tokenGroupIdHex)
            val lpGroupIdBytes = hexToBytes(resolvedLpGroupIdHex)

            // Instantiate AmmDex contract (offline, for serialization)
            val instance = when (val r = sdk.contract.instantiateAmmDex(tradeGroupIdBytes, lpGroupIdBytes, initialLpSupply)) {
                is SdkResult.Success -> r.value
                is SdkResult.Failure -> return DexResult.failure(DexError.PoolDeployFailed("instantiate: ${r.error}"))
            }

            val amount = when (val r = NexaAmount.fromSatoshis(initialNexSats)) {
                is SdkResult.Success -> r.value
                is SdkResult.Failure -> return DexResult.failure(DexError.InternalError("Invalid amount"))
            }

            // Deploy pool: creates pool UTXO + LP reserve UTXO + user LP tokens
            val deployResult = when (val r = sdk.contract.deployAmmDexPool(
                wallet, tokenGroupIdHex, resolvedLpGroupIdHex, amount, initialTokenAmount, initialLpSupply,
            )) {
                is SdkResult.Success -> r.value
                is SdkResult.Failure -> return DexResult.failure(DexError.PoolDeployFailed("deploy: ${r.error}"))
            }

            val txIdStr = deployResult.txId
            val contractAddr = deployResult.contractAddress
            logger.info("Pool deployed: txId={}, address={}, userLpTokens={}", txIdStr, contractAddr, deployResult.userLpTokens)

            val blob = ContractInstanceSerializer.serialize(instance)
            val pool = poolRepo.insert(
                Pool(
                    tokenGroupIdHex = tokenGroupIdHex,
                    lpGroupIdHex = resolvedLpGroupIdHex,
                    initialLpSupply = initialLpSupply,
                    contractAddress = contractAddr,
                    contractBlob = blob,
                    status = PoolStatus.ACTIVE,
                    nexReserve = initialNexSats,
                    tokenReserve = initialTokenAmount,
                    deployTxId = txIdStr,
                    poolUtxoTxId = txIdStr,
                    poolUtxoVout = 0,
                    contractVersion = "v3",
                ),
            )

            val lpAddress = when (val r = sdk.wallet.deriveAddress(wallet)) {
                is SdkResult.Success -> r.value.cashAddr
                is SdkResult.Failure -> "unknown"
            }
            lpShareRepo.insert(
                LpShare(
                    poolId = pool.poolId,
                    providerAddress = lpAddress,
                    nexContributed = initialNexSats,
                    tokensContributed = initialTokenAmount,
                    sharePct = 100.0,
                    depositTxId = txIdStr,
                ),
            )

            instanceCache[pool.poolId] = instance
            poolMutexes[pool.poolId] = Mutex()

            logger.info("Pool created: id={}, token={}, lpGroup={}", pool.poolId, tokenGroupIdHex, resolvedLpGroupIdHex)
            DexResult.success(pool)
        } catch (e: Exception) {
            logger.error("Pool creation failed", e)
            DexResult.failure(DexError.PoolDeployFailed("Unexpected: ${e.message}"))
        } finally {
            try { sdk.wallet.destroy(wallet) } catch (e: Exception) { logger.warn("Wallet destroy failed: {}", e.message) }
        }
    }

    /**
     * Auto-create an LP token group and mint tokens.
     * Returns the LP group ID hex, or null on failure.
     */
    private suspend fun autoCreateLpGroup(
        wallet: org.nexa.sdk.types.wallet.Wallet,
        tokenGroupIdHex: String,
        lpSupply: Long,
    ): String? {
        val token = tokenRepo.findByGroupId(tokenGroupIdHex)
        val ticker = token?.ticker ?: "LP"
        val lpTicker = if (ticker.length <= 6) "${ticker}LP" else ticker.take(6) + "LP"
        val lpName = "${ticker} LP Token"

        val lpMetadata = TokenMetadata(
            ticker = lpTicker,
            name = if (lpName.length > 25) lpName.take(25) else lpName,
            decimals = 0,
        )

        // Step 1: Create LP token group
        logger.info("Creating LP group: ticker={}, supply={}", lpTicker, lpSupply)
        val createResult = when (val r = sdk.token.createGroup(wallet, lpMetadata, lpSupply)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                logger.error("Failed to create LP group: {}", r.error)
                return null
            }
        }
        val lpGroupIdHex = createResult.groupIdHex
        logger.info("LP group created: {}, tx={}", lpGroupIdHex, createResult.broadcastResult.txIdem.hex)

        // Wait for UTXO propagation
        logger.info("Waiting 30s for LP group UTXO propagation...")
        delay(30_000)

        // Step 2: Mint LP tokens
        logger.info("Minting {} LP tokens...", lpSupply)
        val lpHash = lpGroupIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val lpTokenId = when (val r = TokenId.fromHash(Network.MAINNET, lpHash)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                logger.error("Failed to parse LP token ID: {}", r.error)
                return null
            }
        }
        val address = when (val r = sdk.wallet.deriveAddress(wallet)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                logger.error("Failed to derive address: {}", r.error)
                return null
            }
        }
        when (val r = sdk.token.mint(wallet, lpTokenId, lpSupply, address)) {
            is SdkResult.Success -> logger.info("LP tokens minted: tx={}", r.value.txIdem.hex)
            is SdkResult.Failure -> {
                logger.error("Failed to mint LP tokens: {}", r.error)
                return null
            }
        }

        // Wait for UTXO propagation
        logger.info("Waiting 30s for LP mint UTXO propagation...")
        delay(30_000)

        return lpGroupIdHex
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
