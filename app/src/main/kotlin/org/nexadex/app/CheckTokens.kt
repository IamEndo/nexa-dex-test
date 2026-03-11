package org.nexadex.app

import kotlinx.coroutines.runBlocking
import org.nexa.sdk.NexaSDK
import org.nexa.sdk.ensureSdkInitialized
import org.nexa.sdk.types.common.SdkResult
import org.nexa.sdk.types.config.ConnectionConfig
import org.nexa.sdk.types.config.ServerConfig
import org.nexa.sdk.types.wallet.Mnemonic
import org.nexa.sdk.types.wallet.Network

fun main(args: Array<String>) {
    val mnemonicPhrase = args.firstOrNull() ?: return
    ensureSdkInitialized()
    val sdk = NexaSDK

    runBlocking {
        sdk.connection.connect(ConnectionConfig(
            network = Network.MAINNET,
            servers = listOf(ServerConfig(host = "electrum.nexa.org", port = 20004, useSsl = true)),
        ))

        val mnemonic = (Mnemonic.parse(mnemonicPhrase) as SdkResult.Success).value
        val wallet = (sdk.wallet.createFromMnemonic(mnemonic, Network.MAINNET) as SdkResult.Success).value

        // NEX balance
        val balance = sdk.query.getBalanceForWallet(wallet)
        println("NEX balance: $balance")

        // Token balance
        val tokenBal = sdk.token.getBalance(wallet)
        println("Token balances: $tokenBal")

        // Token UTXOs
        val tokenUtxos = sdk.token.getUtxos(wallet, null)
        when (tokenUtxos) {
            is SdkResult.Success -> {
                println("Token UTXOs (${tokenUtxos.value.size}):")
                tokenUtxos.value.forEach {
                    println("  tokenId: ${it.tokenId.cashAddr}")
                    println("    hashHex: ${it.tokenId.hashHex}")
                    println("    amount: ${it.tokenAmount}")
                    println("    auth: ${it.isAuthority}")
                    println()
                }
            }
            is SdkResult.Failure -> println("Token UTXOs error: ${tokenUtxos.error}")
        }

        sdk.wallet.destroy(wallet)
        sdk.connection.disconnect()
    }
}
