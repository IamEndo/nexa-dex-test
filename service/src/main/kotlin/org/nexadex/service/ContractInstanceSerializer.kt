package org.nexadex.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nexadex.core.model.ContractAbiInputBlob
import org.nexadex.core.model.ContractBlob
import org.nexadex.core.model.ContractFunctionBlob
import org.nexadex.core.model.ContractParamBlob
import org.nexa.sdk.types.contract.*

/**
 * Serializes/deserializes SDK ContractInstance to/from a JSON blob for DB storage.
 */
object ContractInstanceSerializer {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun serialize(instance: ContractInstance): ByteArray {
        val blob = ContractBlob(
            templateName = instance.template.name,
            templateBytecodeHex = instance.template.bytecode.toHex(),
            templateHashHex = instance.template.templateHash.toHex(),
            constraintScriptHex = instance.constraintScript.toHex(),
            constraintHashHex = instance.constraintHash.toHex(),
            contractAddress = instance.address.cashAddr,
            args = instance.args,
            functions = instance.template.functions.map { fn ->
                ContractFunctionBlob(
                    name = fn.name,
                    selectorIndex = fn.selectorIndex,
                    params = fn.params.map { p ->
                        ContractParamBlob(name = p.name, type = p.type.name)
                    },
                    usesCltv = fn.usesCltv,
                )
            },
            constructorInputs = instance.template.constructorInputs.map { inp ->
                ContractAbiInputBlob(name = inp.name, type = inp.type)
            },
        )
        return json.encodeToString(blob).toByteArray(Charsets.UTF_8)
    }

    fun deserialize(bytes: ByteArray): ContractInstance {
        val blob = json.decodeFromString<ContractBlob>(String(bytes, Charsets.UTF_8))

        val template = ContractTemplate(
            name = blob.templateName,
            bytecode = blob.templateBytecodeHex.hexToBytes(),
            templateHash = blob.templateHashHex.hexToBytes(),
            functions = blob.functions.map { fn ->
                ContractFunction(
                    name = fn.name,
                    selectorIndex = fn.selectorIndex,
                    params = fn.params.map { p ->
                        ContractParam(
                            name = p.name,
                            type = ContractParamType.valueOf(p.type),
                        )
                    },
                    usesCltv = fn.usesCltv,
                )
            },
            constructorInputs = blob.constructorInputs.map { inp ->
                AbiInput(
                    name = inp.name,
                    type = inp.type,
                )
            },
        )

        return ContractInstance(
            template = template,
            constraintScript = blob.constraintScriptHex.hexToBytes(),
            constraintHash = blob.constraintHashHex.hexToBytes(),
            address = org.nexa.sdk.types.primitives.Address.parse(blob.contractAddress).let {
                when (it) {
                    is org.nexa.sdk.types.common.SdkResult.Success -> it.value
                    is org.nexa.sdk.types.common.SdkResult.Failure -> throw IllegalStateException("Invalid contract address: ${blob.contractAddress}")
                }
            },
            args = blob.args,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
