package org.nexadex.tools

import kotlinx.coroutines.runBlocking
import org.nexa.sdk.NexaSDK
import org.nexa.sdk.ensureSdkInitialized
import org.nexa.sdk.types.common.SdkResult

fun main() = runBlocking {
    ensureSdkInitialized()

    val mnemonic = when (val r = NexaSDK.wallet.generateMnemonic()) {
        is SdkResult.Success -> r.value
        is SdkResult.Failure -> { println("generateMnemonic failed: ${r.error}"); return@runBlocking }
    }

    val wallet = when (val r = NexaSDK.wallet.createFromMnemonic(mnemonic)) {
        is SdkResult.Success -> r.value
        is SdkResult.Failure -> { println("createFromMnemonic failed: ${r.error}"); return@runBlocking }
    }

    val address = when (val r = NexaSDK.wallet.deriveAddress(wallet)) {
        is SdkResult.Success -> r.value.cashAddr
        is SdkResult.Failure -> { println("deriveAddress failed: ${r.error}"); return@runBlocking }
    }

    val pubkey = when (val r = NexaSDK.wallet.getPublicKey(wallet)) {
        is SdkResult.Success -> r.value.hex
        is SdkResult.Failure -> { println("getPublicKey failed: ${r.error}"); return@runBlocking }
    }

    println("MNEMONIC=${mnemonic.toPhrase()}")
    println("ADDRESS=$address")
    println("PUBKEY=$pubkey")
}
