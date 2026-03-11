package org.nexadex.app

import org.nexa.sdk.NexaSDK
import org.nexa.sdk.ensureSdkInitialized
import org.nexa.sdk.types.common.SdkResult
import org.nexa.sdk.types.wallet.MnemonicStrength
import org.nexa.sdk.types.wallet.Network

fun main() {
    ensureSdkInitialized()
    val sdk = NexaSDK

    val mnemonic = when (val r = sdk.wallet.generateMnemonic(MnemonicStrength.WORDS_12)) {
        is SdkResult.Success -> r.value
        is SdkResult.Failure -> {
            println("Failed to generate mnemonic: ${r.error}")
            return
        }
    }

    val wallet = when (val r = sdk.wallet.createFromMnemonic(mnemonic, Network.MAINNET)) {
        is SdkResult.Success -> r.value
        is SdkResult.Failure -> {
            println("Failed to create wallet: ${r.error}")
            return
        }
    }

    val address = when (val r = sdk.wallet.deriveAddress(wallet)) {
        is SdkResult.Success -> r.value
        is SdkResult.Failure -> {
            println("Failed to derive address: ${r.error}")
            return
        }
    }

    println("=== New Wallet ===")
    println("Mnemonic: ${mnemonic.toPhrase()}")
    println("Address:  ${address.cashAddr}")

    sdk.wallet.destroy(wallet)
}
