package org.nexadex.app

import kotlinx.coroutines.runBlocking
import org.nexa.sdk.NexaSDK
import org.nexa.sdk.ensureSdkInitialized
import org.nexa.sdk.types.common.SdkResult
import org.nexa.sdk.types.config.ConnectionConfig
import org.nexa.sdk.types.config.ServerConfig
import org.nexa.sdk.types.primitives.NexaAmount
import org.nexa.sdk.types.primitives.TokenId
import org.nexa.sdk.types.token.TokenMetadata
import org.nexa.sdk.types.wallet.Mnemonic
import org.nexa.sdk.types.wallet.Network

/**
 * Deploy a new PIC/NEX pool with proper LP reserve allocation.
 *
 * 1. Create new LP group (authority)
 * 2. Mint 1B LP tokens
 * 3. Deploy pool: 2M sats NEX + 2M PIC, initialLpSupply=1B
 *
 * With the sqrt fix: userLP = sqrt(2M*2M) = 2M, reserve = 998M
 *
 * Usage: java -cp app.jar org.nexadex.app.DeployPoolV2Kt <mnemonic words>
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: java -cp app.jar org.nexadex.app.DeployPoolV2Kt \"word1 word2 ... word12\"")
        return
    }

    val mnemonicPhrase = args.joinToString(" ")
    val picGroupIdHex = "c69fa651404795f0e3ea4e80fd22114bedf1f56e342745d486cbd438aa910000"
    val poolNexSats = 2_000_000L      // 20,000 NEX
    val poolTokenAmount = 2_000_000L  // 2M PIC
    val lpSupply = 1_000_000_000L     // 1 billion LP tokens

    println("=== Deploy Pool V2 (with LP reserve fix) ===")
    println("  PIC group: $picGroupIdHex")
    println("  Pool NEX:  $poolNexSats sats")
    println("  Pool PIC:  $poolTokenAmount")
    println("  LP supply: $lpSupply")
    val expectedUserLp = Math.sqrt(poolNexSats.toDouble() * poolTokenAmount.toDouble()).toLong()
    println("  Expected user LP: $expectedUserLp (sqrt)")
    println("  Expected reserve: ${lpSupply - expectedUserLp}")
    println()

    ensureSdkInitialized()
    val sdk = NexaSDK

    runBlocking {
        // Connect
        println("[1/5] Connecting to Rostrum...")
        sdk.connection.connect(ConnectionConfig(
            network = Network.MAINNET,
            servers = listOf(ServerConfig(host = "electrum.nexa.org", port = 20004, useSsl = true)),
        )).let {
            when (it) {
                is SdkResult.Success -> println("  Connected!")
                is SdkResult.Failure -> { println("  FAILED: ${it.error}"); return@runBlocking }
            }
        }

        val mnemonic = (Mnemonic.parse(mnemonicPhrase) as? SdkResult.Success)?.value
            ?: run { println("  Invalid mnemonic"); return@runBlocking }
        val wallet = (sdk.wallet.createFromMnemonic(mnemonic, Network.MAINNET) as? SdkResult.Success)?.value
            ?: run { println("  Failed to create wallet"); return@runBlocking }
        val address = (sdk.wallet.deriveAddress(wallet) as? SdkResult.Success)?.value
            ?: run { println("  Failed to derive address"); return@runBlocking }
        println("  Address: ${address.cashAddr}")

        // Check balance
        println("\n[2/5] Checking balances...")
        when (val r = sdk.query.getBalanceForWallet(wallet)) {
            is SdkResult.Success -> println("  NEX: ${r.value.confirmed.satoshis} sats")
            is SdkResult.Failure -> { println("  FAILED: ${r.error}"); sdk.wallet.destroy(wallet); return@runBlocking }
        }
        when (val r = sdk.token.getBalance(wallet)) {
            is SdkResult.Success -> println("  Tokens: ${r.value}")
            is SdkResult.Failure -> println("  Token balance error: ${r.error}")
        }

        // Create new LP group
        println("\n[3/5] Creating new LP token group (1B supply)...")
        val lpMetadata = TokenMetadata(
            ticker = "PICLP2",
            name = "Piccolo LP v2",
            decimals = 0,
        )
        val lpGroupResult = when (val r = sdk.token.createGroup(wallet, lpMetadata, lpSupply)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                println("  FAILED: ${r.error}")
                sdk.wallet.destroy(wallet)
                return@runBlocking
            }
        }
        val lpGroupIdHex = lpGroupResult.groupIdHex
        println("  LP group ID: $lpGroupIdHex")
        println("  LP create TX: ${lpGroupResult.broadcastResult.txIdem.hex}")

        println("  Waiting 30s for UTXO propagation...")
        kotlinx.coroutines.delay(30_000)

        // Mint LP tokens
        println("\n[4/5] Minting $lpSupply LP tokens...")
        val lpHash = lpGroupIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val lpTokenId = (TokenId.fromHash(Network.MAINNET, lpHash) as? SdkResult.Success)?.value
            ?: run { println("  Failed to parse LP token ID"); sdk.wallet.destroy(wallet); return@runBlocking }

        when (val r = sdk.token.mint(wallet, lpTokenId, lpSupply, address)) {
            is SdkResult.Success -> println("  LP mint TX: ${r.value.txIdem.hex}")
            is SdkResult.Failure -> {
                println("  FAILED: ${r.error}")
                sdk.wallet.destroy(wallet)
                return@runBlocking
            }
        }

        println("  Waiting 30s for UTXO propagation...")
        kotlinx.coroutines.delay(30_000)

        // Deploy pool
        println("\n[5/5] Deploying AmmDex pool...")
        val nexForPool = (NexaAmount.fromSatoshis(poolNexSats) as SdkResult.Success).value

        when (val r = sdk.contract.deployAmmDexPool(
            wallet = wallet,
            tradeGroupId = picGroupIdHex,
            lpGroupId = lpGroupIdHex,
            initialNex = nexForPool,
            initialTokenAmount = poolTokenAmount,
            initialLpSupply = lpSupply,
        )) {
            is SdkResult.Success -> {
                val result = r.value
                println()
                println("=== POOL DEPLOYED ===")
                println("  Contract: ${result.contractAddress}")
                println("  Deploy TX: ${result.txId}")
                println("  User LP tokens: ${result.userLpTokens}")
                println("  LP reserve: ${lpSupply - result.userLpTokens}")
                println()
                println("PIC group:  $picGroupIdHex")
                println("LP group:   $lpGroupIdHex")
                println("LP supply:  $lpSupply")
                println()
                println("Register on DEX:")
                println("  POST /api/v1/pools")
                println("  {")
                println("    \"tokenGroupIdHex\": \"$picGroupIdHex\",")
                println("    \"lpGroupIdHex\": \"$lpGroupIdHex\",")
                println("    \"initialLpSupply\": $lpSupply,")
                println("    \"initialNexSats\": $poolNexSats,")
                println("    \"initialTokenAmount\": $poolTokenAmount")
                println("  }")
            }
            is SdkResult.Failure -> println("  FAILED: ${r.error}")
        }

        sdk.wallet.destroy(wallet)
        sdk.connection.disconnect()
    }
}
