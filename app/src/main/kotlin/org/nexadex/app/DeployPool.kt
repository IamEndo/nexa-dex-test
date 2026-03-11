package org.nexadex.app

import kotlinx.coroutines.runBlocking
import org.nexa.sdk.NexaSDK
import org.nexa.sdk.ensureSdkInitialized
import org.nexa.sdk.types.common.SdkResult
import org.nexa.sdk.types.config.ConnectionConfig
import org.nexa.sdk.types.config.ServerConfig
import org.nexa.sdk.types.primitives.NexaAmount
import org.nexa.sdk.types.primitives.TokenId
import org.nexa.sdk.types.wallet.Mnemonic
import org.nexa.sdk.types.wallet.Network

/**
 * Mint token supply and deploy pool.
 * Groups are already created (authority UTXOs exist).
 * This mints the actual supply, then deploys the pool.
 *
 * Usage: java -cp app.jar org.nexadex.app.DeployPoolKt <mnemonic> <picGroupId> <lpGroupId>
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: java -cp app.jar org.nexadex.app.DeployPoolKt \"mnemonic\" picGroupId lpGroupId")
        return
    }

    val mnemonicPhrase = args[0]
    val picGroupIdHex = args[1]
    val lpGroupIdHex = args[2]

    println("=== Mint & Deploy Pool ===")
    println("  PIC group: $picGroupIdHex")
    println("  LP group:  $lpGroupIdHex")

    ensureSdkInitialized()
    val sdk = NexaSDK

    runBlocking {
        // Connect
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

        // Check current token state
        println("\n[1/4] Checking token balances...")
        val tokenBal = sdk.token.getBalance(wallet)
        println("  Token balances: $tokenBal")

        val tokenUtxos = sdk.token.getUtxos(wallet, null)
        when (tokenUtxos) {
            is SdkResult.Success -> {
                println("  Token UTXOs: ${tokenUtxos.value.size}")
                tokenUtxos.value.forEach {
                    println("    - ${it.tokenId.cashAddr}: amount=${it.tokenAmount}, auth=${it.isAuthority}")
                }
            }
            is SdkResult.Failure -> println("  Failed to get token UTXOs: ${tokenUtxos.error}")
        }

        // Parse token IDs from hex - full 32-byte group ID
        val picHash = picGroupIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val lpHash = lpGroupIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val picTokenId = (TokenId.fromHash(Network.MAINNET, picHash) as? SdkResult.Success)?.value
            ?: run { println("  Failed to parse PIC token ID"); return@runBlocking }
        val lpTokenId = (TokenId.fromHash(Network.MAINNET, lpHash) as? SdkResult.Success)?.value
            ?: run { println("  Failed to parse LP token ID"); return@runBlocking }

        println("  PIC token ID: ${picTokenId.cashAddr}")
        println("  LP token ID:  ${lpTokenId.cashAddr}")

        // Mint PIC supply (1B)
        println("\n[2/4] Minting 1,000,000,000 PIC tokens...")
        when (val r = sdk.token.mint(wallet, picTokenId, 1_000_000_000L, address)) {
            is SdkResult.Success -> println("  PIC mint TX: ${r.value.txIdem.hex}")
            is SdkResult.Failure -> {
                println("  FAILED: ${r.error}")
                sdk.wallet.destroy(wallet)
                return@runBlocking
            }
        }

        println("  Waiting 30s for propagation...")
        kotlinx.coroutines.delay(30_000)

        // Mint LP supply (10k)
        println("\n[3/4] Minting 10,000 LP tokens...")
        when (val r = sdk.token.mint(wallet, lpTokenId, 10_000L, address)) {
            is SdkResult.Success -> println("  LP mint TX: ${r.value.txIdem.hex}")
            is SdkResult.Failure -> {
                println("  FAILED: ${r.error}")
                sdk.wallet.destroy(wallet)
                return@runBlocking
            }
        }

        println("  Waiting 30s for propagation...")
        kotlinx.coroutines.delay(30_000)

        // Verify tokens are now visible
        println("\n  Verifying token balances...")
        when (val r = sdk.token.getBalance(wallet)) {
            is SdkResult.Success -> println("  Balances: ${r.value}")
            is SdkResult.Failure -> println("  Warning: ${r.error}")
        }

        // Deploy pool
        println("\n[4/4] Deploying AmmDex pool (1B PIC + 100k NEX)...")
        val poolNexSats = 10_000_000L
        val nexForPool = (NexaAmount.fromSatoshis(poolNexSats) as SdkResult.Success).value

        when (val r = sdk.contract.deployAmmDexPool(
            wallet = wallet,
            tradeGroupId = picGroupIdHex,
            lpGroupId = lpGroupIdHex,
            initialNex = nexForPool,
            initialTokenAmount = 1_000_000_000L,
            initialLpSupply = 10_000L,
        )) {
            is SdkResult.Success -> {
                val result = r.value
                println("")
                println("=== POOL DEPLOYED ===")
                println("  Contract: ${result.contractAddress}")
                println("  Deploy TX: ${result.txId}")
                println("  User LP tokens: ${result.userLpTokens}")
                println("")
                println("PIC group:  $picGroupIdHex")
                println("LP group:   $lpGroupIdHex")
            }
            is SdkResult.Failure -> println("  FAILED: ${r.error}")
        }

        sdk.wallet.destroy(wallet)
        sdk.connection.disconnect()
    }
}
