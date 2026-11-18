package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.ManagedPlugin
import com.labteto.dshmobile.core.wire.dto.PluginBundle
import com.labteto.dshmobile.core.wire.dto.PluginBundleRow
import com.labteto.dshmobile.data.PluginOperationState

/** Immutable confirmation: approval scripts and registry can never come from the editable form. */
/** [replaces] names the installed bundle an update supersedes, for hosts that will not install over it. */
internal data class PluginInstallSubject(val target: String, val registry: String?, val approvedBuilds: List<String>? = null,
    val enabled: Boolean = false, val saveExact: Boolean? = null, val replaces: String? = null)

internal fun com.labteto.dshmobile.core.wire.dto.PluginBundle.catalogInstall(enabled: Boolean): PluginInstallSubject? =
    installTarget?.let { PluginInstallSubject(it.spec, null, enabled = enabled,
        saveExact = true.takeUnless { _ -> it.spec.startsWith("link:") }, replaces = name.takeIf { installed }) }
internal fun PluginOperationState.approvalSubject(): PluginInstallSubject? =
    subject?.takeIf { it.isNotBlank() }?.let { target ->
        result?.pendingBuilds?.takeIf { it.isNotEmpty() }?.let { PluginInstallSubject(target, registry, it.toList(), enabled, saveExact) }
    }
internal fun PluginOperationState.displaySubject(): String? = subject ?: result?.let { it.bundle ?: it.target }?.takeIf { it.isNotBlank() }

internal fun pluginReason(code: String?): Int = when (code) {
    "management-required" -> R.string.ux_a_reason_required
    "unaddressable" -> R.string.ux_a_reason_unaddressable
    "unknown-plugin", "not-found" -> R.string.ux_a_reason_missing
    "invalid-spec" -> R.string.ux_a_reason_spec
    "ambiguous-install" -> R.string.ux_a_reason_ambiguous
    "not-bundle", "not-a-bundle" -> R.string.ux_a_reason_bundle
    "already-installed" -> R.string.ux_a_reason_installed
    "network" -> R.string.ux_a_reason_network
    "not-a-package" -> R.string.ux_a_reason_package
    "not-removable" -> R.string.ux_a_reason_removable
    "stop-profile" -> R.string.ux_a_reason_stop
    "bundle-in-use" -> R.string.ux_a_reason_in_use
    "stale-approval" -> R.string.ux_a_reason_approval
    "incompatible-version" -> R.string.ux_a_reason_version
    else -> R.string.ux_a_action_error
}
internal fun pluginPhase(phase: String?): Int = when (phase) {
    "starting", "queued", "resolving" -> R.string.ux_a_phase_starting
    "running", "installing" -> R.string.ux_a_phase_installing
    "applying" -> R.string.ux_a_phase_applying
    "cancelling", "cancel-requested", "requested" -> R.string.ux_a_phase_cancelling
    "cancelled" -> R.string.harness_cancelled
    "done", "completed" -> R.string.harness_applied
    "failed" -> R.string.harness_failed
    else -> R.string.common_loading
}

// ---- Plugins page: short names, filters, search and detail grouping. All pure, so the two
// composables (the list and the bundle detail) stay thin and the rules are unit tested. ----

private const val PLUGIN_SCOPE = "@deepseek-ai/"
private const val PLUGIN_NAME_PREFIX = "dsh-"
private const val FILE_URL_PREFIX = "file://"

/** A bundle with more rows than this gets its own search and filter chips on its detail page. */
internal const val PLUGIN_DETAIL_CONTROLS_AFTER = 8

/** How many matching module names a collapsed bundle card names before it says "+N more". */
internal const val PLUGIN_MATCH_PREVIEW = 3

internal fun pluginDetailShowsControls(rowCount: Int): Boolean = rowCount > PLUGIN_DETAIL_CONTROLS_AFTER

/**
 * The part of a package name that tells it apart: the `@deepseek-ai/` scope and one leading `dsh-`
 * are noise on this screen. A `file://` URL becomes its last path segment. Any other name (another
 * scope, `cordis:include`, a plain name) is returned as it is. Display only; never an identifier.
 */
internal fun pluginShortName(name: String): String {
    if (name.startsWith(FILE_URL_PREFIX)) {
        return name.removePrefix(FILE_URL_PREFIX).trimEnd('/').substringAfterLast('/').ifEmpty { name }
    }
    if (!name.startsWith(PLUGIN_SCOPE)) return name
    return name.removePrefix(PLUGIN_SCOPE).removePrefix(PLUGIN_NAME_PREFIX).ifEmpty { name }
}

/**
 * Short names for the items of one list. Two different names that would shorten to the same text
 * would be indistinguishable, so those items keep their full name.
 */
internal fun pluginShortNames(names: Iterable<String>): Map<String, String> {
    val distinct = names.distinct()
    val short = distinct.associateWith(::pluginShortName)
    val clashing = short.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return distinct.associateWith { name -> short.getValue(name).takeUnless { it in clashing } ?: name }
}

/** The On / Off chips. A row that has no managed plugin behind it has no state and only shows under All. */
internal enum class PluginFilter {
    ALL, ON, OFF;

    fun accepts(enabled: Boolean?): Boolean = when (this) {
        ALL -> true
        ON -> enabled == true
        OFF -> enabled == false
    }
}

internal data class PluginFilterCounts(val all: Int, val on: Int, val off: Int) {
    operator fun get(filter: PluginFilter): Int = when (filter) {
        PluginFilter.ALL -> all
        PluginFilter.ON -> on
        PluginFilter.OFF -> off
    }
}

internal fun pluginFilterCounts(states: Iterable<Boolean?>): PluginFilterCounts {
    var all = 0; var on = 0; var off = 0
    states.forEach { state -> all++; if (state == true) on++ else if (state == false) off++ }
    return PluginFilterCounts(all, on, off)
}

/** The bundle's own name or description matches (the match that does not need its module rows). */
internal fun PluginBundle.matchesOwnText(query: String): Boolean =
    name.contains(query, true) || description.orEmpty().contains(query, true)

/** Module rows whose full module name contains [query]; an empty query matches no row on purpose. */
internal fun PluginBundle.matchedRows(query: String): List<PluginBundleRow> =
    if (query.isEmpty()) emptyList() else rows.filter { it.moduleName.contains(query, true) }

internal fun PluginBundle.matchesSearch(query: String): Boolean = matchesOwnText(query) || matchedRows(query).isNotEmpty()

/** Installed or Available, as the list has always grouped a bundle. */
internal fun PluginBundle.listedAsInstalled(): Boolean = availability?.let { it != "missing" } ?: installed

/** The module names under a bundle card when the search matched its rows and not the bundle itself. */
internal data class PluginRowMatches(val names: List<String>, val more: Int)

internal fun PluginBundle.rowMatches(query: String, limit: Int = PLUGIN_MATCH_PREVIEW, shortName: (String) -> String): PluginRowMatches? {
    if (!matchedOnlyByRows(query)) return null
    val hits = matchedRows(query)
    return PluginRowMatches(hits.take(limit).map { shortName(it.moduleName) }, (hits.size - limit).coerceAtLeast(0))
}

/**
 * The search text a bundle's detail page opens with. Only a bundle that matched through its module
 * rows carries the text over (its card named the rows); one that matched by its own name or
 * description would open with every row filtered out.
 */
internal fun PluginBundle.searchCarriedToDetail(query: String): String = if (matchedOnlyByRows(query)) query else ""

private fun PluginBundle.matchedOnlyByRows(query: String): Boolean =
    query.isNotEmpty() && !matchesOwnText(query) && matchedRows(query).isNotEmpty()

/** What the list page draws, after the search text and the chip are applied. */
internal data class PluginListModel(
    /** Bundles that pass the search and the chip, in the order the harness sent them. */
    val bundles: List<PluginBundle>,
    /** Managed plugins that belong to no bundle and pass the search and the chip. */
    val standalone: List<ManagedPlugin>,
    /** Bundles plus standalone plugins after the search, before the chip. */
    val counts: PluginFilterCounts,
    /** Full name to the name drawn. Computed over the whole list, so a chip never renames an item. */
    val names: Map<String, String>,
)

internal fun pluginListModel(bundles: List<PluginBundle>, plugins: List<ManagedPlugin>, query: String, filter: PluginFilter): PluginListModel {
    val uniqueBundles = bundles.distinctBy { it.name }
    val bundled = uniqueBundles.flatMap { it.rows }.mapNotNull { it.entryId }.toSet()
    val allStandalone = plugins.distinctBy { it.entryId }.filter { it.entryId !in bundled }
    val matchingBundles = uniqueBundles.filter { it.matchesSearch(query) }
    val matchingStandalone = allStandalone.filter { it.moduleName.contains(query, true) }
    return PluginListModel(
        bundles = matchingBundles.filter { filter.accepts(it.enabled) },
        standalone = matchingStandalone.filter { filter.accepts(it.enabled) },
        counts = pluginFilterCounts(matchingBundles.map { it.enabled } + matchingStandalone.map { it.enabled }),
        names = pluginShortNames(uniqueBundles.map { it.name } + allStandalone.map { it.moduleName }),
    )
}

/** One module row of a bundle; [plugin] is null when the harness manages nothing behind it. */
internal data class PluginModuleRow(val row: PluginBundleRow, val plugin: ManagedPlugin?) {
    val enabled: Boolean? get() = plugin?.enabled
}

internal fun pluginModuleRows(bundle: PluginBundle, plugins: Map<String, ManagedPlugin>): List<PluginModuleRow> =
    bundle.rows.distinctBy { it.rowId }.map { PluginModuleRow(it, it.entryId?.let(plugins::get)) }

/** "N of M on": the enabled rows out of the rows that map to a managed plugin; null when none do. */
internal data class PluginEnabledCount(val on: Int, val total: Int)

internal fun pluginEnabledCount(rows: List<PluginModuleRow>): PluginEnabledCount? {
    val managed = rows.mapNotNull { it.plugin }
    return if (managed.isEmpty()) null else PluginEnabledCount(managed.count { it.enabled }, managed.size)
}

/** Locked rows that share one reason, drawn under that reason once. */
internal data class PluginLockedGroup(val reason: String, val rows: List<PluginModuleRow>)

internal data class PluginDetailModel(
    /** Rows after the search, before the chip. Plain rows only count under All. */
    val counts: PluginFilterCounts,
    /** Rows with a switch, in their original order. */
    val toggleable: List<PluginModuleRow>,
    /** Locked rows, one group per distinct reason, in the order the reasons first appear. */
    val locked: List<PluginLockedGroup>,
    /** Rows with no managed plugin behind them. */
    val plain: List<PluginModuleRow>,
) {
    val isEmpty: Boolean get() = toggleable.isEmpty() && locked.isEmpty() && plain.isEmpty()
}

internal fun pluginDetailModel(rows: List<PluginModuleRow>, query: String, filter: PluginFilter): PluginDetailModel {
    val matching = rows.filter { it.row.moduleName.contains(query, true) }
    val shown = matching.filter { filter.accepts(it.enabled) }
    return PluginDetailModel(
        counts = pluginFilterCounts(matching.map { it.enabled }),
        toggleable = shown.filter { it.plugin != null && it.plugin.readOnlyReason == null },
        locked = shown.mapNotNull { row -> row.plugin?.readOnlyReason?.let { it to row } }
            .groupBy({ it.first }, { it.second })
            .map { (reason, group) -> PluginLockedGroup(reason, group) },
        plain = shown.filter { it.plugin == null },
    )
}
