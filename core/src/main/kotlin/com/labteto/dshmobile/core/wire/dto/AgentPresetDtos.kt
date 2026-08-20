package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Agent-preset DTOs, ported from `packages/host/apiproxy/src/api/agent-presets.schema.ts` and
 * `packages/host/apiproxy/src/api/agent-presets.ts` (v0.1.0-rc.8).
 */

/** Trust tier of an agent preset. */
@Serializable
enum class AgentPresetTrust {
    @SerialName("system")
    SYSTEM,

    @SerialName("user")
    USER,
}

/** AgentPresetEntry row of `agentPreset.list`. */
@Serializable
data class AgentPresetEntry(
    @SerialName("id") val id: String,
    @SerialName("trust") val trust: AgentPresetTrust,
    @SerialName("isDefault") val isDefault: Boolean,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    /** Present when the preset is known but its composition cannot be mounted. */
    @SerialName("broken") val broken: String? = null,
)

/** Value of `agentPreset.list`. */
@Serializable
data class AgentPresetListValue(
    @SerialName("presets") val presets: List<AgentPresetEntry> = emptyList(),
    @SerialName("authorable") val authorable: Boolean,
    @SerialName("hasDocument") val hasDocument: Boolean,
)

/** Request payload of `agentPreset.select`. */
@Serializable
data class AgentPresetSelectRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("agentPreset") val agentPreset: String,
)

/** Value of `agentPreset.select`. */
@Serializable
data class AgentPresetSelectValue(
    @SerialName("agentPreset") val agentPreset: String,
)

/** Request payload of `agentPreset.read`. */
@Serializable
data class AgentPresetReadRequest(
    @SerialName("agentPreset") val agentPreset: String,
)

/** Value of `agentPreset.read`. */
@Serializable
data class AgentPresetReadValue(
    @SerialName("agentPreset") val agentPreset: String,
    @SerialName("trust") val trust: AgentPresetTrust,
    @SerialName("content") val content: String,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
)

/** Request payload of `agentPreset.copy`. */
@Serializable
data class AgentPresetCopyRequest(
    @SerialName("from") val from: String,
    @SerialName("agentPreset") val agentPreset: String,
    @SerialName("name") val name: String? = null,
)

/** Value of `agentPreset.copy`. */
@Serializable
data class AgentPresetCopyValue(
    @SerialName("agentPreset") val agentPreset: String,
)

/** Request payload of `agentPreset.openDocument`. */
@Serializable
data class AgentPresetOpenDocumentRequest(
    @SerialName("agentPreset") val agentPreset: String,
)

/**
 * Value of `agentPreset.openDocument`: `{opened: true}` when the document opened,
 * `{opened: false, path}` when the deployment cannot open it (the path names the document).
 */
@Serializable
data class AgentPresetOpenDocumentValue(
    @SerialName("opened") val opened: Boolean,
    @SerialName("path") val path: String? = null,
)

/** Request payload of `agentPreset.remove`. */
@Serializable
data class AgentPresetRemoveRequest(
    @SerialName("agentPreset") val agentPreset: String,
)

/** Value of `agentPreset.remove` (empty object). */
@Serializable
class AgentPresetRemoveValue
