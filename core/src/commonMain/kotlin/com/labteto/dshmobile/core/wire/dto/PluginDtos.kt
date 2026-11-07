package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plugin-inventory DTOs, ported from `packages/host/plugin-inventory/src/types.ts`.
 *
 * This inventory remains read-only. Harness 0.2.1 adds writes under the separate
 * pluginManager namespace; see Harness021Dtos and Harness021Api.
 */

/** Where a plugin has got to in the Cordis loader's lifecycle. Absent means it never mounted. */
@Serializable
enum class PluginFiberPhase {
    @SerialName("pending")
    PENDING,

    @SerialName("loading")
    LOADING,

    @SerialName("active")
    ACTIVE,

    @SerialName("failed")
    FAILED,

    @SerialName("unloading")
    UNLOADING,
}

/** One row of `pluginInventory/list`. */
@Serializable
data class PluginInventoryEntry(
    /** The loader-tree entry id — stable, and what a `cordis.patch.yml` override targets. */
    @SerialName("entryId") val entryId: String,
    /** The exact module specifier, e.g. `@deepseek-ai/dsh-client-ui-plan`. */
    @SerialName("moduleName") val moduleName: String,
    /** Effective enablement, already folded through any disabled ancestor group. */
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("fiberPhase") val fiberPhase: PluginFiberPhase? = null,
)

/** Value of `pluginInventory/list`. */
@Serializable
data class PluginInventorySnapshot(
    @SerialName("entries") val entries: List<PluginInventoryEntry> = emptyList(),
)
