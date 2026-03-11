package org.nexadex.app

import kotlinx.coroutines.runBlocking
import org.nexa.sdk.NexaSDK
import org.nexa.sdk.ensureSdkInitialized
import org.nexa.sdk.types.common.SdkResult
import org.nexa.sdk.types.config.ConnectionConfig
import org.nexa.sdk.types.config.ServerConfig
import org.nexa.sdk.types.primitives.NexaAmount
import org.nexa.sdk.types.token.TokenMetadata
import org.nexa.sdk.types.wallet.Mnemonic
import org.nexa.sdk.types.wallet.Network

/**
 * CLI tool to set up a new pool on the DEX.
 *
 * Usage: java -cp app.jar org.nexadex.app.SetupPoolKt <mnemonic>
 *
 * This will:
 * 1. Check wallet NEX balance
 * 2. Mint PIC token group (1,000,000,000 supply)
 * 3. Mint LP token group (10,000 supply)
 * 4. Deploy AmmDex pool with all PIC tokens + 100k NEX
 * 5. Print all IDs for DEX registration
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: java -cp app.jar org.nexadex.app.SetupPoolKt \"word1 word2 ... word12\"")
        return
    }

    val mnemonicPhrase = args.joinToString(" ")
    println("=== NexaDEX Pool Setup ===")

    ensureSdkInitialized()
    val sdk = NexaSDK

    // Connect to Rostrum
    println("[1/6] Connecting to Rostrum...")
    runBlocking {
        val connConfig = ConnectionConfig(
            network = Network.MAINNET,
            servers = listOf(
                ServerConfig(host = "electrum.nexa.org", port = 20004, useSsl = true),
            ),
        )
        when (val r = sdk.connection.connect(connConfig)) {
            is SdkResult.Success -> println("  Connected!")
            is SdkResult.Failure -> {
                println("  FAILED: ${r.error}")
                return@runBlocking
            }
        }

        // Parse mnemonic & create wallet
        val mnemonic = when (val r = Mnemonic.parse(mnemonicPhrase)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                println("  Invalid mnemonic: ${r.error}")
                return@runBlocking
            }
        }
        val wallet = when (val r = sdk.wallet.createFromMnemonic(mnemonic, Network.MAINNET)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                println("  Failed to create wallet: ${r.error}")
                return@runBlocking
            }
        }

        val address = when (val r = sdk.wallet.deriveAddress(wallet)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                println("  Failed to derive address: ${r.error}")
                return@runBlocking
            }
        }
        println("  Wallet address: ${address.cashAddr}")

        // Check balance
        println("[2/6] Checking NEX balance...")
        val balance = when (val r = sdk.query.getBalanceForWallet(wallet)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                println("  Failed to get balance: ${r.error}")
                return@runBlocking
            }
        }
        val nexSats = balance.confirmed.satoshis
        val nexAmount = nexSats / 100.0
        println("  Balance: $nexAmount NEX ($nexSats sats)")

        // Need 100k NEX = 10,000,000 sats + fees (~50,000 sats for 4 txs)
        val poolNexSats = 10_000_000L  // 100,000 NEX
        val feeBuffer = 100_000L       // fees for mint + deploy txs
        val totalNeeded = poolNexSats + feeBuffer
        if (nexSats < totalNeeded) {
            println("  INSUFFICIENT: need ${totalNeeded / 100.0} NEX, have $nexAmount NEX")
            sdk.wallet.destroy(wallet)
            return@runBlocking
        }
        println("  Sufficient for pool ($nexAmount NEX >= ${totalNeeded / 100.0} NEX needed)")

        // Mint PIC token
        println("[3/6] Minting PIC token (1,000,000,000 supply)...")
        val picMetadata = TokenMetadata(
            ticker = "PIC",
            name = "Piccolo",
            decimals = 0,
        )
        val picResult = when (val r = sdk.token.createGroup(wallet, picMetadata, 1_000_000_000L)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                println("  FAILED to mint PIC: ${r.error}")
                sdk.wallet.destroy(wallet)
                return@runBlocking
            }
        }
        val picGroupId = picResult.groupIdHex
        println("  PIC group ID: $picGroupId")
        println("  PIC mint txId: ${picResult.broadcastResult.txIdem.hex}")

        // Wait for UTXO propagation
        println("  Waiting 30s for UTXO propagation...")
        kotlinx.coroutines.delay(30_000)

        // Mint LP token
        println("[4/6] Minting LP token (10,000 supply)...")
        val lpMetadata = TokenMetadata(
            ticker = "PICLP",
            name = "Piccolo LP",
            decimals = 0,
        )
        val lpResult = when (val r = sdk.token.createGroup(wallet, lpMetadata, 10_000L)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                println("  FAILED to mint LP token: ${r.error}")
                sdk.wallet.destroy(wallet)
                return@runBlocking
            }
        }
        val lpGroupId = lpResult.groupIdHex
        println("  LP group ID: $lpGroupId")
        println("  LP mint txId: ${lpResult.broadcastResult.txIdem.hex}")

        // Wait for UTXO propagation
        println("  Waiting 30s for UTXO propagation...")
        kotlinx.coroutines.delay(30_000)

        // Deploy AmmDex pool
        println("[5/6] Deploying AmmDex pool (1B PIC + 100k NEX)...")
        val nexForPool = when (val r = NexaAmount.fromSatoshis(poolNexSats)) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                println("  Invalid amount: ${r.error}")
                sdk.wallet.destroy(wallet)
                return@runBlocking
            }
        }

        val deployResult = when (val r = sdk.contract.deployAmmDexPool(
            wallet = wallet,
            tradeGroupId = picGroupId,
            lpGroupId = lpGroupId,
            initialNex = nexForPool,
            initialTokenAmount = 1_000_000_000L,
            initialLpSupply = 10_000L,
        )) {
            is SdkResult.Success -> r.value
            is SdkResult.Failure -> {
                println("  FAILED to deploy pool: ${r.error}")
                sdk.wallet.destroy(wallet)
                return@runBlocking
            }
        }

        println("  Pool deployed!")
        println("  Contract address: ${deployResult.contractAddress}")
        println("  Deploy txId: ${deployResult.txId}")
        println("  User LP tokens: ${deployResult.userLpTokens}")

        // Print summary
        println("")
        println("[6/6] === POOL SETUP COMPLETE ===")
        println("")
        println("Trade Token (PIC):")
        println("  Group ID: $picGroupId")
        println("  Supply:   1,000,000,000")
        println("  Decimals: 0")
        println("")
        println("LP Token (PICLP):")
        println("  Group ID: $lpGroupId")
        println("  Supply:   10,000")
        println("  User LP:  ${deployResult.userLpTokens}")
        println("")
        println("Pool:")
        println("  Contract: ${deployResult.contractAddress}")
        println("  Deploy TX: ${deployResult.txId}")
        println("  NEX:      100,000 NEX (${poolNexSats} sats)")
        println("  PIC:      1,000,000,000")
        println("")
        println("Register token on DEX:")
        println("  curl -X POST https://nexa-dex-backend-production.up.railway.app/api/v1/tokens \\")
        println("    -H 'Content-Type: application/json' \\")
        println("    -d '{\"groupIdHex\":\"$picGroupId\",\"name\":\"Piccolo\",\"ticker\":\"PIC\",\"decimals\":0}'")
        println("")
        println("Create pool on DEX (already deployed on-chain, this registers it):")
        println("  Use POST /api/v1/pools with:")
        println("    tokenGroupIdHex: $picGroupId")
        println("    lpGroupIdHex: $lpGroupId")
        println("    initialLpSupply: 10000")
        println("    initialNexSats: $poolNexSats")
        println("    initialTokenAmount: 1000000000")

        sdk.wallet.destroy(wallet)
        sdk.connection.disconnect()
    }
}
