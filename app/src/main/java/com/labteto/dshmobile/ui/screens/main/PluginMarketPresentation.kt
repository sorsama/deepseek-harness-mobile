package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.market.MarketCheck
import com.labteto.dshmobile.core.market.MarketEntry
import com.labteto.dshmobile.core.market.MarketFailure
import com.labteto.dshmobile.core.market.MarketHeader
import com.labteto.dshmobile.core.market.MarketIndexInfo
import com.labteto.dshmobile.core.market.MarketInstallSource
import com.labteto.dshmobile.core.market.MarketInstallStatus
import com.labteto.dshmobile.core.market.MarketInstallation
import com.labteto.dshmobile.core.market.MarketPage
import com.labteto.dshmobile.core.market.MarketQuery
import com.labteto.dshmobile.core.market.MarketVersion

// ---- Plugins page, Discover view: what a card, a chip, the entry page and the confirmation say. All
// pure, like PluginPresentation.kt, so the composables stay thin and the rules are unit tested. ----

/** The two views of the Plugins page. Each keeps its own search text and scroll position. */
internal enum class PluginView { INSTALLED, DISCOVER }

/** The state tag on a card: a word, never only a colour. */
internal enum class MarketCardState { NONE, INSTALLED, UPDATE_AVAILABLE, PROVIDED, NEEDS_NEWER }

/** What one Discover card draws. [technicalTitle] is a package name (no title in the index), drawn as such. */
internal data class MarketCard(
    val name: String,
    val title: String,
    val technicalTitle: Boolean,
    val description: String?,
    val version: String?,
    val publisher: String?,
    val state: MarketCardState,
)

/**
 * The host of an http(s) link, for a publisher line when the index names no author. Display only: the link
 * itself is what opens.
 */
internal fun marketHost(url: String?): String? {
    val rest = url?.substringAfter("://", "") ?: return null
    val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
    return host.removePrefix("www.").takeIf { it.isNotEmpty() }
}

/** The author, else the host of the homepage, else of the source code page. */
internal fun marketPublisher(entry: MarketEntry): String? = entry.author ?: marketHost(entry.homepage) ?: marketHost(entry.repository?.url)

internal fun marketCardState(entry: MarketEntry, installation: MarketInstallation): MarketCardState = when (installation.status) {
    MarketInstallStatus.INSTALLED, MarketInstallStatus.DIFFERENT_VERSION -> MarketCardState.INSTALLED
    MarketInstallStatus.UPDATE_AVAILABLE -> MarketCardState.UPDATE_AVAILABLE
    MarketInstallStatus.PROVIDED -> MarketCardState.PROVIDED
    MarketInstallStatus.NOT_INSTALLED, MarketInstallStatus.UNKNOWN ->
        if (entry.presented?.compatibility?.installable == false) MarketCardState.NEEDS_NEWER else MarketCardState.NONE
}

/** The first line of a description, for a one-line card. */
internal fun marketFirstLine(text: String?): String? = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

/** The title the index gives, else the package's short name (the rule the Installed list uses). */
internal fun marketCard(entry: MarketEntry, installation: MarketInstallation, languages: List<String>): MarketCard {
    val title = entry.title?.resolve(languages)
    return MarketCard(
        name = entry.name,
        title = title ?: pluginShortName(entry.name),
        technicalTitle = title == null,
        description = marketFirstLine(entry.description?.resolve(languages)),
        version = entry.presented?.version,
        publisher = marketPublisher(entry),
        state = marketCardState(entry, installation),
    )
}

// ---- Filter chips ----

internal enum class MarketChipKind { ALL, CATEGORY, LABEL }

internal data class MarketChip(val kind: MarketChipKind, val id: String, val title: String, val selected: Boolean)

/**
 * The chips a market offers, only when it declares categories or labels: All, then its categories (one at a
 * time), then its labels (they add up). Declarations are in the order of their title in the person's language,
 * as the index format asks. Labels are the market's own words and carry no trust of their own.
 */
internal fun marketChips(header: MarketHeader, query: MarketQuery, languages: List<String>, allTitle: String): List<MarketChip> {
    if (header.categories.isEmpty() && header.labels.isEmpty()) return emptyList()
    fun ordered(declarations: List<com.labteto.dshmobile.core.market.MarketDeclaration>) =
        declarations.map { it.id to (it.title.resolve(languages) ?: it.id) }
            .sortedWith(compareBy<Pair<String, String>, String>(String.CASE_INSENSITIVE_ORDER) { it.second }.thenBy { it.first })
    return buildList {
        add(MarketChip(MarketChipKind.ALL, "", allTitle, query.category == null && query.labels.isEmpty()))
        ordered(header.categories).forEach { (id, title) -> add(MarketChip(MarketChipKind.CATEGORY, id, title, query.category == id)) }
        ordered(header.labels).forEach { (id, title) -> add(MarketChip(MarketChipKind.LABEL, id, title, id in query.labels)) }
    }
}

/** What tapping a chip does to the query: All clears the filters, a category replaces or clears, a label toggles. */
internal fun MarketQuery.tap(chip: MarketChip): MarketQuery = when (chip.kind) {
    MarketChipKind.ALL -> withoutFilters()
    MarketChipKind.CATEGORY -> toggleCategory(chip.id)
    MarketChipKind.LABEL -> toggleLabel(chip.id)
}

// ---- The list's own messages ----

internal enum class MarketEmpty { INDEX, MATCHES }

/** A market with nothing in it, or a search or chip that found nothing: each has its own plain message. */
internal fun marketEmpty(page: MarketPage?, answered: MarketQuery?): MarketEmpty? = when {
    page == null || page.total > 0 || page.entries.isNotEmpty() -> null
    answered == null || answered.isUnfiltered -> MarketEmpty.INDEX
    else -> MarketEmpty.MATCHES
}

/** The sentence for a failed read, in the host's own terms. The host's reason goes to the details, not here. */
internal fun marketFailureText(failure: MarketFailure): Int = when (failure) {
    is MarketFailure.Fetch -> R.string.market_failed_fetch
    is MarketFailure.InvalidIndex -> R.string.market_failed_invalid
    MarketFailure.UnknownIndex -> R.string.market_failed_missing
    MarketFailure.Changed -> R.string.market_failed_changed
    else -> R.string.ux_a_load_error
}

/** The technical lines under a failure: the host's code and words, which an index owner's server may have written. */
internal fun marketFailureDetails(failure: MarketFailure): String? = when (failure) {
    is MarketFailure.Fetch -> failure.reason
    is MarketFailure.InvalidIndex -> listOfNotNull(failure.path, failure.reason).joinToString("\n").ifBlank { null }
    is MarketFailure.Other -> listOfNotNull(failure.code, failure.message).joinToString("\n")
    else -> null
}

/**
 * What the Markets page says is wrong with one market: the host's own words, or what this session saw fail, unless the
 * market has loaded since. The host keeps the last error it recorded for a market it was not asked about again, so a
 * market that loaded is not in error whatever the host still lists.
 */
internal fun marketSourceProblem(hostError: String?, loadedSince: Boolean, failure: MarketFailure?): String? =
    if (loadedSince) null else hostError ?: failure?.let(::marketFailureDetails)

/** The name a failure is shown under: the market's title in the person's language, else the host of its address. */
internal fun marketLabel(url: String, sources: List<MarketIndexInfo>?, languages: List<String>): String? =
    sources?.firstOrNull { it.url == url }?.title?.resolve(languages) ?: marketHost(url)

/** Why a market source was refused when it was added. */
internal fun marketSourceFailureText(failure: MarketFailure): Int = when (failure) {
    is MarketFailure.InvalidUrl -> if (failure.reason == "duplicate") R.string.market_source_duplicate else R.string.market_source_invalid
    MarketFailure.SettingsUnavailable -> R.string.market_source_unavailable
    else -> R.string.market_source_failed
}

// ---- The entry page ----

/** The one primary action an entry offers. */
internal sealed interface MarketAction {
    data class Install(val version: MarketVersion) : MarketAction

    /** An installed older version: [bundle] is the installed bundle the update replaces. */
    data class Update(val version: MarketVersion, val bundle: String?) : MarketAction

    /** Installed and current (or a different version the market does not offer over it): open the bundle's own page. */
    data class OpenInstalled(val bundle: String) : MarketAction

    /** Nothing to do and no page to open: the harness ships it, or it is installed but not in the list yet. */
    data object None : MarketAction

    /** The harness does not accept any version of it. */
    data object Incompatible : MarketAction
}

/**
 * The action for an entry. [installedBundle] is the name of the bundle the Plugins page lists as installed for
 * this package, or null. The host's installation status decides which of install, update or open it is.
 */
internal fun marketAction(entry: MarketEntry, installation: MarketInstallation, installedBundle: String?): MarketAction {
    val version = entry.presented
    return when (installation.status) {
        MarketInstallStatus.INSTALLED, MarketInstallStatus.DIFFERENT_VERSION, MarketInstallStatus.PROVIDED ->
            installedBundle?.let { MarketAction.OpenInstalled(it) } ?: MarketAction.None
        MarketInstallStatus.UPDATE_AVAILABLE ->
            if (version?.compatibility?.installable == true) MarketAction.Update(version, installedBundle) else MarketAction.Incompatible
        MarketInstallStatus.NOT_INSTALLED, MarketInstallStatus.UNKNOWN ->
            if (version?.compatibility?.installable == true) MarketAction.Install(version) else MarketAction.Incompatible
    }
}

/**
 * How far the app trusts an entry. The index format has no official or verified mark: labels are a market's own
 * opaque words, so a market cannot be believed about itself. The only entries that are not [UNVERIFIED] are the
 * ones the harness itself says it ships, which there is nothing to install for.
 */
internal enum class MarketTrust { UNVERIFIED, SHIPPED }

internal fun marketTrust(installation: MarketInstallation): MarketTrust =
    if (installation.status == MarketInstallStatus.PROVIDED) MarketTrust.SHIPPED else MarketTrust.UNVERIFIED

internal sealed interface MarketCompat {
    data class Works(val range: String?) : MarketCompat
    data class Needs(val requirement: String?, val runtime: String?) : MarketCompat
    data object Allowed : MarketCompat
}

internal fun marketCompat(version: MarketVersion): MarketCompat = with(version.compatibility) {
    when {
        compatible -> MarketCompat.Works(version.dshRange)
        exempted -> MarketCompat.Allowed
        else -> MarketCompat.Needs(requirement ?: version.dshRange, runtimeVersion)
    }
}

/** One line of "about this plugin" on the entry page. [link] makes the value open in the browser. */
internal data class MarketFact(val label: Int, val value: String, val technical: Boolean = false, val link: String? = null)

/**
 * The facts a person checks before installing: who publishes it, where it comes from (exactly), when, and under
 * which market. [date] formats a Unix time in the person's language.
 */
internal fun marketFacts(
    entry: MarketEntry,
    version: MarketVersion,
    header: MarketHeader?,
    installation: MarketInstallation,
    languages: List<String>,
    date: (Long) -> String,
): List<MarketFact> = buildList {
    entry.author?.let { add(MarketFact(R.string.market_fact_publisher, it)) }
    entry.homepage?.let { add(MarketFact(R.string.market_fact_homepage, it, technical = true, link = it)) }
    entry.repository?.let { repo ->
        val where = repo.directory?.let { "${repo.url} · $it" } ?: repo.url
        add(MarketFact(R.string.market_fact_source_code, where, technical = true, link = repo.url))
    }
    when (val source = version.source) {
        is MarketInstallSource.Npm -> {
            add(MarketFact(R.string.market_fact_package, version.installSpec, technical = true))
            source.registry?.let { add(MarketFact(R.string.market_fact_registry, it, technical = true)) }
        }
        is MarketInstallSource.Git -> {
            add(MarketFact(R.string.market_fact_repository, source.url, technical = true))
            add(MarketFact(R.string.market_fact_commit, source.commit, technical = true))
            source.path?.let { add(MarketFact(R.string.market_fact_folder, it, technical = true)) }
        }
    }
    if (version.publishedAt > 0) add(MarketFact(R.string.market_fact_published, date(version.publishedAt)))
    val category = entry.category?.let { id -> header?.categories?.firstOrNull { it.id == id }?.title?.resolve(languages) ?: id }
    category?.let { add(MarketFact(R.string.market_fact_category, it)) }
    val labels = entry.labels.map { id -> header?.labels?.firstOrNull { it.id == id }?.title?.resolve(languages) ?: id }
    if (labels.isNotEmpty()) add(MarketFact(R.string.market_fact_labels, labels.joinToString(", ")))
    header?.title?.resolve(languages)?.let { add(MarketFact(R.string.market_fact_market, it)) }
    installation.installedVersion?.let { add(MarketFact(R.string.market_fact_installed, it, technical = true)) }
}

// ---- Installing: the same path as the manual install, with what the market claimed shown beside the spec ----

/**
 * What the confirmation shows about a market entry, next to the exact package spec and registry: what it is called,
 * which version, who publishes it, and which market listed it. [unverified] is the one-line warning.
 */
internal data class MarketInstallNote(
    val title: String,
    val version: String,
    val publisher: String?,
    val market: String?,
    val unverified: Boolean,
    val exempted: Boolean,
    /** Set for a git install: the repository and the pinned commit, which is what it installs from. A git source has no registry. */
    val git: MarketGitNote? = null,
)

/** Where a git install comes from: the repository and the exact commit it is pinned to. */
internal data class MarketGitNote(val url: String, val commit: String)

internal fun marketInstallNote(
    entry: MarketEntry,
    version: MarketVersion,
    header: MarketHeader?,
    installation: MarketInstallation,
    languages: List<String>,
): MarketInstallNote = MarketInstallNote(
    title = entry.title?.resolve(languages) ?: pluginShortName(entry.name),
    version = version.version,
    publisher = entry.author,
    market = header?.title?.resolve(languages),
    unverified = marketTrust(installation) == MarketTrust.UNVERIFIED,
    exempted = !version.compatibility.compatible && version.compatibility.exempted,
    git = (version.source as? MarketInstallSource.Git)?.let { MarketGitNote(it.url, it.commit) },
)

/**
 * The install the confirmation dialog confirms, built from the exact spec and registry the harness gave for the
 * version: never from the display name. Installs disabled (as the manual install does) and pinned exactly, as the
 * web client's installer does for anything that is not a local path. [replaces] names the installed bundle an
 * update supersedes.
 */
internal fun marketInstallSubject(version: MarketVersion, note: MarketInstallNote, replaces: String?): PluginInstallSubject =
    PluginInstallSubject(
        target = version.installSpec,
        registry = version.installRegistry,
        enabled = false,
        saveExact = true,
        replaces = replaces,
        market = note,
    )

/**
 * The sentence for a check that did not clear the install, or null when it did. Each outcome the harness's answer can tell
 * apart has its own: the package is not what the market listed, the package was not found, the harness has no package
 * manager, the harness's package tool failed (that one is fixed on the harness computer, so it never says "refresh"), and
 * the package source could not be reached. A refusal with any other code keeps the text the manual install gives it.
 */
internal fun marketCheckProblem(check: MarketCheck): Int? = when (check) {
    MarketCheck.Proceed -> null
    is MarketCheck.Mismatch -> R.string.market_check_mismatch
    MarketCheck.NoPackageManager -> R.string.market_no_pnpm
    is MarketCheck.ToolFailed -> R.string.market_tool_failed
    is MarketCheck.Refused -> when (check.problem) {
        "not-found" -> R.string.market_check_not_found
        "network" -> R.string.ux_a_reason_network
        else -> pluginReason(check.problem).takeUnless { it == R.string.ux_a_action_error } ?: R.string.market_check_failed
    }
}

/** The technical lines under a check that did not clear the install: the harness's own code and words. */
internal fun marketCheckDetails(check: MarketCheck): String? = when (check) {
    is MarketCheck.Mismatch -> listOfNotNull(check.name, check.version).joinToString(" ").ifBlank { null }
    is MarketCheck.Refused -> listOfNotNull(check.problem, check.reason).joinToString("\n").ifBlank { null }
    is MarketCheck.ToolFailed -> listOfNotNull(check.problem, check.reason).joinToString("\n").ifBlank { null }
    else -> null
}
