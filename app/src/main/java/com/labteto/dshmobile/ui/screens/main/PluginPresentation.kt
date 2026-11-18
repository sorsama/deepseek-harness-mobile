package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.ManagedPlugin
import com.labteto.dshmobile.core.wire.dto.PluginBundle
import com.labteto.dshmobile.core.wire.dto.PluginBundleRow
import com.labteto.dshmobile.data.PluginOperationState

/** Immutable confirmation: approval scripts and registry can never come from the editable form. */
/** [replaces] names the installed bundle an update supersedes, for hosts that will not install over it. */
/** [market] is set when the install comes from a market entry: what the confirmation shows beside the spec. */
internal data class PluginInstallSubject(val target: String, val registry: String?, val approvedBuilds: List<String>? = null,
    val enabled: Boolean = false, val saveExact: Boolean? = null, val replaces: String? = null, val market: MarketInstallNote? = null)

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
    "pnpm-missing" -> R.string.market_no_pnpm
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
    // One module can be loaded by two rows (see [pluginRowQualifiers]); the card names it once.
    val hits = matchedRows(query).map { shortName(it.moduleName) }.distinct()
    return PluginRowMatches(hits.take(limit), (hits.size - limit).coerceAtLeast(0))
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

/**
 * The small second line that tells apart rows of one bundle that name the same module, keyed by row
 * id; rows whose module name is unique get none.
 *
 * A bundle can load one module twice with different settings, and then both rows draw the same
 * module name. The `base` bundle does it for `@deepseek-ai/dsh-tool-subagent`: its patch declares the
 * row `tool-subagent` (the spawn tool) and the row `tool-subagent-fork` (the fork tool) over that one
 * module. What differs is the row id, the id the patch gives the row, so that is the label; the
 * harness's own web page prints it under the title the same way. A row whose id is already the name
 * drawn (`tool-subagent`) needs nothing: it is the one with no second line. [names] is the module
 * name to drawn name map the page uses.
 */
internal fun pluginRowQualifiers(rows: List<PluginModuleRow>, names: Map<String, String>): Map<String, String> =
    rows.groupBy { it.row.moduleName }.values
        .filter { it.size > 1 }
        .flatten()
        .mapNotNull { module ->
            val drawn = names[module.row.moduleName] ?: module.row.moduleName
            module.row.rowId.takeUnless { it == drawn }?.let { module.row.rowId to it }
        }
        .toMap()

/** Why a bundle's rows have no managed plugin behind them, when that is the bundle's state and not the row's. */
internal enum class PluginPlainNote { BUNDLE_OFF, NOT_INSTALLED }

/**
 * What to say once above rows that have no managed plugin. The harness gives a row an entry (and so a
 * switch) only while its bundle is loaded, and its own page lists such rows as Off with the switches
 * shown only when the bundle is on; so for an installed bundle that is off the true sentence is
 * "turn the bundle on". A bundle that is not installed has no files to read rows from, so there is
 * normally nothing to say it about. Null for a bundle that is on: its rows lack an entry for some
 * other reason and the page does not guess which.
 */
internal fun PluginBundle.plainRowsNote(): PluginPlainNote? = when {
    !listedAsInstalled() -> PluginPlainNote.NOT_INSTALLED
    !enabled -> PluginPlainNote.BUNDLE_OFF
    else -> null
}

/** Managed plugins that belong to no bundle: the ones with a switch, and the locked ones grouped by reason. */
internal data class PluginStandaloneModel(val open: List<ManagedPlugin>, val locked: List<PluginLockedPlugins>)

/** Locked standalone plugins that share one reason, drawn under that reason once. */
internal data class PluginLockedPlugins(val reason: String, val plugins: List<ManagedPlugin>)

internal fun pluginStandaloneModel(plugins: List<ManagedPlugin>): PluginStandaloneModel = PluginStandaloneModel(
    open = plugins.filter { it.readOnlyReason == null },
    locked = plugins.mapNotNull { plugin -> plugin.readOnlyReason?.let { it to plugin } }
        .groupBy({ it.first }, { it.second })
        .map { (reason, group) -> PluginLockedPlugins(reason, group) },
)

/**
 * What an operation card calls its subject: the name the list or the bundle page draws for it, when
 * the subject is something on them. A module change names its entry id (`include:repeat-tool-reminder`),
 * a bundle change its full package name (`@deepseek-ai/dsh-experimental-ralph-bundle`); neither fits a
 * card. Anything else (an install spec, an unknown id) is not in the map and keeps its own text.
 * [listNames] is the list model's full name to drawn name map (bundles and standalone plugins).
 */
internal fun pluginSubjectNames(
    bundles: List<PluginBundle>,
    plugins: List<ManagedPlugin>,
    listNames: Map<String, String>,
): Map<String, String> {
    val names = HashMap<String, String>()
    // Modules of a bundle are drawn on its page with names that avoid clashes inside that bundle.
    bundles.distinctBy { it.name }.forEach { bundle ->
        val drawn = pluginShortNames(bundle.rows.map { it.moduleName })
        bundle.rows.forEach { row -> row.entryId?.let { id -> names[id] = drawn.getValue(row.moduleName) } }
    }
    plugins.forEach { plugin -> names.putIfAbsent(plugin.entryId, listNames[plugin.moduleName] ?: pluginShortName(plugin.moduleName)) }
    listNames.forEach { (full, drawn) -> names.putIfAbsent(full, drawn) }
    return names
}

/** The prefixes a module's entry id carries: they say what kind of entry it is, not what it is called. */
private val PLUGIN_ENTRY_ID_PREFIXES = listOf("include:")

/**
 * What a subject is called while the list it would be looked up in has not loaded yet (right after
 * the screen is recreated, for one): a module entry id loses its kind prefix and takes the same
 * short-name rule the list applies, so `include:repeat-tool-reminder` never shows as a raw id and
 * then changes to `repeat-tool-reminder` when the list arrives. Anything else keeps its own text.
 */
internal fun pluginSubjectFallback(subject: String): String {
    val prefix = PLUGIN_ENTRY_ID_PREFIXES.firstOrNull { subject.startsWith(it) && subject.length > it.length } ?: return subject
    return pluginShortName(subject.removePrefix(prefix))
}

/** The name an operation card draws for [subject]: the list's name for it, or the readable fallback. */
internal fun pluginSubjectText(subject: String, names: Map<String, String>): String =
    names[subject] ?: pluginSubjectFallback(subject)

/**
 * A bundle card's line under its name: the version, then the count. Only the separator can break
 * (the space before the dot is a no-break space, and the count string keeps its own words together),
 * so a large font never splits "71 of 96 on" across two lines.
 */
internal fun pluginMetaLine(version: String?, countText: String?): String =
    listOfNotNull(version, countText).joinToString(" · ")

/** The word on an operation's tag. */
internal fun pluginTagLabel(application: String?, busy: Boolean, unknown: Boolean, failed: Boolean): Int = when {
    busy -> R.string.common_loading
    unknown || failed -> R.string.harness_failed
    application == "applied" -> R.string.harness_applied
    application == "restart-required" -> R.string.harness_restart
    application != null -> R.string.harness_failed
    else -> R.string.ux_a_installed
}

/**
 * The line an operation card writes under its subject, or null when it would only repeat the tag
 * ("Applied" under an "Applied" tag, or the same sentence twice for a failure).
 */
internal fun pluginStatusLine(application: String?, busy: Boolean, unknown: Boolean, phase: String?): Int? {
    val failed = application == "failed" || unknown
    val line = when {
        unknown -> R.string.ux_a_check_status
        busy -> pluginPhase(phase)
        else -> when (application) {
            "applied" -> R.string.harness_applied
            "restart-required" -> R.string.harness_restart
            "overridden" -> R.string.harness_overridden
            "cancelled" -> R.string.harness_cancelled
            else -> R.string.harness_failed
        }
    }
    return line.takeUnless { it == pluginTagLabel(application, busy, unknown, failed) }
}
