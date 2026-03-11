package org.nexadex.core.model

import kotlinx.serialization.Serializable

/**
 * Serializable snapshot of a ContractInstance for database storage.
 * Captures all fields needed to reconstruct the SDK ContractInstance.
 */
@Serializable
data class ContractBlob(
    val templateName: String,
    val templateBytecodeHex: String,
    val templateHashHex: String,
    val constraintScriptHex: String,
    val constraintHashHex: String,
    val contractAddress: String,
    val args: Map<String, String> = emptyMap(),
    val functions: List<ContractFunctionBlob> = emptyList(),
    val constructorInputs: List<ContractAbiInputBlob> = emptyList(),
)

@Serializable
data class ContractFunctionBlob(
    val name: String,
    val selectorIndex: Int,
    val params: List<ContractParamBlob>,
    val usesCltv: Boolean = false,
)

@Serializable
data class ContractParamBlob(
    val name: String,
    val type: String,
)

@Serializable
data class ContractAbiInputBlob(
    val name: String,
    val type: String,
)
