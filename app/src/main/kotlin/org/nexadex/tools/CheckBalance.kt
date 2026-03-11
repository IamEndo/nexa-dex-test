package org.nexadex.tools

import kotlinx.coroutines.runBlocking
import org.nexa.sdk.NexaSDK
import org.nexa.sdk.ensureSdkInitialized
import org.nexa.sdk.types.common.SdkResult
import org.nexa.sdk.types.config.ConnectionConfig
import org.nexa.sdk.types.config.ServerConfig
import org.nexa.sdk.types.primitives.TxId
import org.nexa.sdk.types.wallet.Mnemonic
import org.nexa.sdk.types.wallet.Network

fun main() = runBlocking {
    ensureSdkInitialized()

    val connConfig = ConnectionConfig(
        network = Network.MAINNET,
        servers = listOf(ServerConfig(host = "electrum.nexa.org", port = 20004, useSsl = true)),
    )
    when (val r = NexaSDK.connection.connect(connConfig)) {
        is SdkResult.Success -> println("Connected to Rostrum")
        is SdkResult.Failure -> { println("Connect failed: ${r.error}"); return@runBlocking }
    }

    val mnemonic = Mnemonic.fromWords("vacant dumb flag property round leave impose review cactus object song jeans".split(" ")).getOrThrow()

    val wallet = when (val r = NexaSDK.wallet.createFromMnemonic(mnemonic)) {
        is SdkResult.Success -> r.value
        is SdkResult.Failure -> { println("createFromMnemonic failed: ${r.error}"); return@runBlocking }
    }

    val address = when (val r = NexaSDK.wallet.deriveAddress(wallet)) {
        is SdkResult.Success -> r.value
        is SdkResult.Failure -> { println("deriveAddress failed: ${r.error}"); return@runBlocking }
    }
    println("ADDRESS=${address.cashAddr}")

    // Check UTXOs
    when (val r = NexaSDK.query.getUtxosForWallet(wallet)) {
        is SdkResult.Success -> {
            val utxos = r.value
            println("UTXO count: ${utxos.size}")
            var totalSats = 0L
            for (utxo in utxos) {
                println("  $utxo")
                totalSats += utxo.utxo.amount.satoshis
                if (utxo.utxo.hasTokens) {
                    println("    TOKEN: groupId=${utxo.utxo.groupId}, amount=${utxo.utxo.groupAmount}")
                }
            }
            println("TOTAL NEX: $totalSats sats")
        }
        is SdkResult.Failure -> println("getUtxos failed: ${r.error}")
    }

    // Also look up the specific tx
    println("\n--- Checking tx f09850b5... ---")
    val txId = TxId.parse("f09850b56d1548bd373f9c9d43a07469f1ff788fdbd2d85ce172e78cbc1b72fc").getOrThrow()
    when (val r = NexaSDK.transaction.get(txId)) {
        is SdkResult.Success -> println("TX: ${r.value}")
        is SdkResult.Failure -> println("getTx failed: ${r.error}")
    }

    NexaSDK.connection.disconnect()
}
