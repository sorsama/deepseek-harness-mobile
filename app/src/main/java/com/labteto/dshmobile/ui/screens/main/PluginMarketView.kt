package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.market.MarketEntry
import com.labteto.dshmobile.core.market.MarketHeader
import com.labteto.dshmobile.core.market.MarketInstallStatus
import com.labteto.dshmobile.core.market.MarketInstallation
import com.labteto.dshmobile.core.market.MarketLimits
import com.labteto.dshmobile.core.market.marketInstallationFor
import com.labteto.dshmobile.core.market.marketLanguageChain
import com.labteto.dshmobile.core.wire.dto.PluginBundle
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.theme.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// ---- The Discover view of the Plugins page. Cards, the entry page and the sources page are all
// built from the same pieces as the Installed view (PluginManagerScreen.kt), so the two read as one page. ----

/** The language preference the market's texts are resolved with: the app's own locale, then English. */
@Composable
internal fun rememberMarketLanguages(): List<String> {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { marketLanguageChain(locale.toLanguageTag()) }
}

/** The chips a market offers. Scrolls sideways, so a large font or a long title never clips a chip. */
@Composable
internal fun MarketFilterChips(chips: List<MarketChip>, onTap: (MarketChip) -> Unit) {
    if (chips.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        chips.forEach { chip -> DsFilterChip(label = chip.title, selected = chip.selected, onClick = { onTap(chip) }) }
    }
}

/**
 * The Discover list: which market, its plugins as cards, and the plain messages for a market with no plugins,
 * a search with no matches, a read that failed, and a market not set up yet. [browser] holds what was read.
 */
internal fun LazyListScope.marketListItems(
    browser: PluginMarketBrowser,
    bundles: List<PluginBundle>,
    languages: List<String>,
    onOpenEntry: (String) -> Unit,
    onOpenSources: () -> Unit,
) {
    val page = browser.page
    val sources = browser.sources
    val status = browser.status
    val failure = browser.failure
    if (sources != null && sources.isEmpty()) {
        item(key = "market-none") { MarketNoSources(onOpenSources) }
        return
    }
    if (sources != null) item(key = "market-header") {
        MarketHeaderRow(
            title = page?.header?.title?.resolve(languages) ?: sources.firstOrNull { it.url == browser.selectedUrl }?.let { it.title?.resolve(languages) ?: marketHost(it.url) },
            onOpenSources = onOpenSources,
        )
    }
    if (page == null && (status == MarketStatus.LOADING || status == MarketStatus.IDLE)) item(key = "market-skeleton") { SkeletonPluginCards() }
    if (failure != null) item(key = "market-failure") {
        // The market the failure is about, by name: a failure never stands under another market's results. With no
        // results yet it is the page's one card; with results of the same market on screen it is a notice above them.
        val label = browser.failureUrl?.let { marketLabel(it, sources, languages) }
        if (page == null) MarketFailureCard(browser, label) else MarketFailureNotice(browser, label)
    }
    val empty = marketEmpty(page, browser.answered)
    if (page != null && empty != null) item(key = "market-empty") {
        Text(
            stringResource(if (empty == MarketEmpty.INDEX) R.string.market_empty_index else R.string.plugins_no_matches),
            style = DsType.std14,
            color = DsTheme.colors.labelSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.large),
        )
    }
    if (page != null) {
        items(page.entries, key = { "market:${it.name}" }) { entry ->
            val installation = marketInstallationFor(entry, bundles)
            MarketCardView(marketCard(entry, installation, languages), onOpen = { onOpenEntry(entry.name) })
        }
        if (page.dropped > 0) item(key = "market-dropped") {
            Text(stringResource(R.string.market_dropped), style = DsType.small13, color = DsTheme.colors.labelTertiary)
        }
        val capped = page.entries.size >= MarketLimits.MAX_LOADED && page.total > page.entries.size
        if (capped) item(key = "market-capped") {
            Text(
                stringResource(R.string.market_capped, page.entries.size, page.total),
                style = DsType.small13,
                color = DsTheme.colors.labelTertiary,
            )
        }
        if (page.nextOffset != null) item(key = "market-more") {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                if (status == MarketStatus.MORE) FeatureProgress()
                FeatureAction(
                    stringResource(R.string.market_show_more),
                    enabled = status == MarketStatus.READY || status == MarketStatus.ERROR,
                ) { browser.more() }
            }
        }
    }
}

@Composable
private fun MarketHeaderRow(title: String?, onOpenSources: () -> Unit) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(
            technicalDisplay(title.orEmpty()),
            style = DsType.small13Strong,
            color = colors.labelSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        DsButton(stringResource(R.string.market_sources), onOpenSources, variant = DsButtonVariant.Outline, size = DsButtonSize.Small, allowWrap = true)
    }
}

@Composable
private fun MarketNoSources(onOpenSources: () -> Unit) {
    DsCard {
        Text(stringResource(R.string.market_no_sources_title), style = DsType.std14Strong, color = DsTheme.colors.labelPrimary)
        Text(stringResource(R.string.market_no_sources_body), style = DsType.std14, color = DsTheme.colors.labelSecondary)
        DsButton(stringResource(R.string.market_add_first), onOpenSources, variant = DsButtonVariant.Primary, allowWrap = true, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The error card for a market that could not be read at all (nothing of it is on screen), with Retry; the host's reason
 * sits behind "Technical details". [market] is the market it is about, when it is about one.
 */
@Composable
private fun MarketFailureCard(browser: PluginMarketBrowser, market: String?) {
    val failure = browser.failure ?: return
    DsCard {
        market?.let { Text(technicalDisplay(it), style = DsType.small13Strong, color = DsTheme.colors.labelSecondary) }
        Text(stringResource(marketFailureText(failure)), color = DsTheme.colors.error, style = DsType.std14)
        FeatureAction(stringResource(R.string.common_retry), browser.status != MarketStatus.LOADING) { browser.retry() }
        FeatureDiagnostics(marketFailureDetails(failure)?.let(::technicalDisplay))
    }
}

/**
 * A read that failed while the market's own results are on screen (a refresh, a search, the next page): a compact notice
 * above them, named for the market, with Retry. The results stay, as they do for a search that is still running.
 */
@Composable
private fun MarketFailureNotice(browser: PluginMarketBrowser, market: String?) {
    val failure = browser.failure ?: return
    val colors = DsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .background(colors.errorTertiary)
            .border(1.dp, colors.errorBorder, DsShapes.row)
            .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
    ) {
        market?.let { Text(technicalDisplay(it), style = DsType.small13Strong, color = colors.labelPrimary) }
        Text(stringResource(marketFailureText(failure)), color = colors.error, style = DsType.small13)
        FeatureAction(stringResource(R.string.common_retry), browser.status != MarketStatus.LOADING) { browser.retry() }
        FeatureDiagnostics(marketFailureDetails(failure)?.let(::technicalDisplay))
    }
}

/**
 * One plugin of the market: what it is called, its version and publisher, one line about it, and a word for
 * where it stands (Installed, Update available, ...). The same three targets as a bundle card, minus the switch:
 * the text opens the entry, and so does the chevron. Each is at least 48dp tall.
 */
@Composable
private fun MarketCardView(card: MarketCard, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val open = { haptics.tap(); onOpen() }
    val openLabel = stringResource(R.string.plugins_open_details)
    val pressSource = remember { MutableInteractionSource() }
    val meta = listOfNotNull(card.version?.let(::technicalDisplay), card.publisher?.let(::technicalDisplay)).joinToString(" · ")
    // Both tap zones share one source and the card fills itself while it is pressed (no ripple is drawn, see DsCard).
    DsCard(contentPadding = PaddingValues(0.dp), pressSource = pressSource) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(interactionSource = pressSource, indication = null, onClickLabel = openLabel, role = Role.Button, onClick = open)
                    .padding(start = DsSpacing.comfortable, top = DsSpacing.medium, bottom = DsSpacing.medium, end = DsSpacing.small),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                PluginGlyph(size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Two lines before an ellipsis, like a bundle's name: two long titles that differ at the end stay apart.
                    Text(
                        technicalDisplay(card.title),
                        style = if (card.technicalTitle) DsType.mono13 else DsType.std14Strong,
                        color = colors.labelPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meta.isNotEmpty()) Text(meta, style = DsType.small13, color = colors.labelTertiary)
                    card.description?.let { Text(it, style = DsType.small13, color = colors.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (card.state != MarketCardState.NONE) {
                Box(Modifier.fillMaxHeight().padding(horizontal = DsSpacing.tiny), contentAlignment = Alignment.Center) { MarketStateTag(card.state) }
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(36.dp)
                    .clickable(interactionSource = pressSource, indication = null, onClick = open)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                // AutoMirrored: the arrow points to the start in right-to-left locales.
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.labelTertiary)
            }
        }
    }
}

/** The state as a word in a small plate, so it never rests on colour alone. */
@Composable
private fun MarketStateTag(state: MarketCardState) {
    val colors = DsTheme.colors
    val style = when (state) {
        MarketCardState.INSTALLED -> TagStyle(R.string.ux_a_installed, colors.success, colors.successTertiary, colors.successBorder)
        MarketCardState.UPDATE_AVAILABLE -> TagStyle(R.string.market_state_update, colors.warnLabel, colors.warnTertiary, colors.warnBorder)
        MarketCardState.PROVIDED -> TagStyle(R.string.market_state_provided, colors.labelSecondary, colors.bgLayer3, colors.borderL2)
        MarketCardState.NEEDS_NEWER -> TagStyle(R.string.market_state_needs_newer, colors.labelSecondary, colors.bgLayer3, colors.borderL2)
        MarketCardState.NONE -> return
    }
    Text(
        stringResource(style.label),
        style = DsType.caption11Strong,
        color = style.foreground,
        maxLines = 3,
        modifier = Modifier
            .widthIn(max = 104.dp)
            .clip(DsShapes.chip)
            .background(style.background)
            .border(1.dp, style.border, DsShapes.chip)
            .padding(horizontal = DsSpacing.xsmall, vertical = 2.dp),
    )
}

private class TagStyle(
    val label: Int,
    val foreground: androidx.compose.ui.graphics.Color,
    val background: androidx.compose.ui.graphics.Color,
    val border: androidx.compose.ui.graphics.Color,
)

// ---- The entry page ----

/** A fixed list of rows ("Publisher  Example Labs"), each a label over its value. A link row opens in the browser. */
@Composable
private fun MarketFactsCard(facts: List<MarketFact>) {
    val colors = DsTheme.colors
    val uri = LocalUriHandler.current
    val browserLabel = stringResource(R.string.market_open_in_browser)
    DsCard(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        facts.forEachIndexed { index, fact ->
            if (index > 0) PluginRowDivider()
            val link = fact.link
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DsSpacing.touchTarget)
                    .then(if (link == null) Modifier else Modifier.clickable(onClickLabel = browserLabel, role = Role.Button) { runCatching { uri.openUri(link) } })
                    .padding(vertical = DsSpacing.small),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(fact.label), style = DsType.small13, color = colors.labelTertiary)
                Text(
                    if (fact.technical) technicalDisplay(fact.value) else fact.value,
                    style = if (fact.technical) DsType.mono12 else DsType.std14,
                    color = if (link == null) colors.labelPrimary else colors.accent,
                    textDecoration = if (link == null) null else TextDecoration.Underline,
                )
            }
        }
    }
}

/** An icon and a sentence on one line of reading: a warning or a note that must not be missed. */
@Composable
private fun MarketNote(text: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(16.dp), tint = colors.warnText)
        Text(text, style = DsType.small13, color = colors.warnText, modifier = Modifier.weight(1f))
    }
}

/**
 * An entry of the market as a page of its own: what it is, whether it works with this harness, who publishes it and
 * exactly where it installs from, and one action. Installing goes through the Plugins page's own confirmation.
 */
@Composable
internal fun PluginMarketEntryPage(
    entry: MarketEntry,
    header: MarketHeader?,
    installation: MarketInstallation,
    installedBundle: PluginBundle?,
    languages: List<String>,
    onBuiltIn: Boolean,
    busy: Boolean,
    check: MarketCheckState,
    onInstall: (MarketAction) -> Unit,
    onOpenInstalled: (String) -> Unit,
    onDismissCheck: () -> Unit,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = DsTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val card = marketCard(entry, installation, languages)
    val action = marketAction(entry, installation, installedBundle?.name)
    val version = entry.presented
    val checking = check is MarketCheckState.Checking && check.name == entry.name
    val facts = remember(entry, header, installation, languages, locale) {
        version?.let { marketFacts(entry, it, header, installation, languages) { seconds -> formatMarketDate(seconds, locale) } }.orEmpty()
    }
    val description = entry.description?.resolve(languages)
    val listState = remember(entry.name) { androidx.compose.foundation.lazy.LazyListState() }
    LazyFeaturePage(
        title = technicalDisplay(card.title),
        subtitle = listOfNotNull(technicalDisplay(entry.name), card.version?.let(::technicalDisplay)).joinToString(" · "),
        onClose = onClose,
        onRefresh = onRefresh,
        progress = busy || checking,
        listState = listState,
    ) {
        item(key = "summary") {
            DsCard {
                if (card.state != MarketCardState.NONE) MarketStateTag(card.state)
                description?.let { Text(it, style = DsType.std14, color = colors.labelSecondary) }
                version?.let { MarketCompatLine(marketCompat(it)) }
                MarketInstalledLine(installation, version?.version)
            }
        }
        item(key = "action") {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                when (action) {
                    is MarketAction.Install, is MarketAction.Update -> DsButton(
                        text = stringResource(
                            when {
                                checking -> R.string.market_checking
                                action is MarketAction.Update -> R.string.ux_a_update
                                else -> R.string.market_install
                            },
                        ),
                        onClick = { onInstall(action) },
                        enabled = !busy && !checking,
                        variant = DsButtonVariant.Primary,
                        allowWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    is MarketAction.OpenInstalled -> DsButton(
                        text = stringResource(R.string.market_open_installed),
                        onClick = { onOpenInstalled(action.bundle) },
                        variant = DsButtonVariant.Outline,
                        allowWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MarketAction.None, MarketAction.Incompatible -> Unit
                }
                if (marketTrust(installation) == MarketTrust.UNVERIFIED) MarketNote(stringResource(R.string.market_unverified))
                if (onBuiltIn && action is MarketAction.Install) MarketNote(stringResource(R.string.market_builtin_note))
            }
        }
        if (check is MarketCheckState.Problem && check.name == entry.name) item(key = "check") { MarketCheckCard(check, onDismissCheck) }
        if (check is MarketCheckState.Failed && check.name == entry.name) item(key = "check-failed") {
            DsCard {
                Text(stringResource(R.string.market_check_failed), color = colors.error, style = DsType.std14)
                FeatureAction(stringResource(R.string.common_close)) { onDismissCheck() }
                FeatureDiagnostics(check.message)
            }
        }
        if (facts.isNotEmpty()) item(key = "facts") { MarketFactsCard(facts) }
    }
}

@Composable
private fun MarketCompatLine(compat: MarketCompat) {
    val colors = DsTheme.colors
    when (compat) {
        is MarketCompat.Works -> Text(stringResource(R.string.market_compat_works), style = DsType.small13, color = colors.labelSecondary)
        MarketCompat.Allowed -> MarketNote(stringResource(R.string.market_compat_allowed))
        is MarketCompat.Needs -> {
            val needs = compat.requirement?.let(::technicalDisplay)
            val runtime = compat.runtime?.let(::technicalDisplay)
            val text = when {
                needs != null && runtime != null -> stringResource(R.string.market_compat_needs, needs, runtime)
                needs != null -> stringResource(R.string.market_compat_needs_short, needs)
                else -> stringResource(R.string.market_state_needs_newer)
            }
            MarketNote(text)
        }
    }
}

@Composable
private fun MarketInstalledLine(installation: MarketInstallation, listed: String?) {
    val colors = DsTheme.colors
    val installed = installation.installedVersion?.let(::technicalDisplay)
    val text = when (installation.status) {
        MarketInstallStatus.PROVIDED -> stringResource(R.string.market_provided_line)
        MarketInstallStatus.UPDATE_AVAILABLE, MarketInstallStatus.DIFFERENT_VERSION ->
            if (installed != null && listed != null) stringResource(R.string.market_versions_line, installed, technicalDisplay(listed)) else null
        else -> null
    } ?: return
    Text(text, style = DsType.small13, color = colors.labelSecondary)
}

/** A check that did not clear the install: what the harness found, in its own words under "Technical details". */
@Composable
private fun MarketCheckCard(problem: MarketCheckState.Problem, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    val message = marketCheckProblem(problem.check) ?: return
    val details = marketCheckDetails(problem.check)
    DsCard {
        Text(
            if (problem.check is com.labteto.dshmobile.core.market.MarketCheck.Mismatch) {
                stringResource(message, technicalDisplay(problem.check.name.orEmpty()), technicalDisplay(problem.check.version.orEmpty()))
            } else stringResource(message),
            color = colors.error,
            style = DsType.std14,
        )
        FeatureAction(stringResource(R.string.common_close)) { onDismiss() }
        FeatureDiagnostics(details?.let(::technicalDisplay))
    }
}

private fun formatMarketDate(seconds: Long, locale: Locale): String =
    runCatching { DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(seconds * 1000)) }.getOrDefault("")

// ---- The confirmation, beside the exact package ----

/**
 * What the Plugins page's confirmation adds for an install that comes from a market: what it is called and who
 * publishes it (the market's claims, below the exact package and registry the install will use), which registry
 * answers when the market named none, and the one-line warning.
 */
@Composable
internal fun MarketConfirmNotes(note: MarketInstallNote, registryNamed: Boolean, defaultRegistry: String?) {
    val colors = DsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(technicalDisplay("${note.title} · ${note.version}"), style = DsType.std14, color = colors.labelPrimary)
        note.publisher?.let { Text(stringResource(R.string.market_line_publisher, technicalDisplay(it)), style = DsType.small13, color = colors.labelSecondary) }
        note.market?.let { Text(stringResource(R.string.market_line_market, technicalDisplay(it)), style = DsType.small13, color = colors.labelSecondary) }
        val git = note.git
        if (git != null) {
            // A git source has no registry: what matters is where it comes from and the exact commit it is pinned to.
            Text(stringResource(R.string.market_line_repository, technicalDisplay(git.url)), style = DsType.small13, color = colors.labelSecondary)
            Text(stringResource(R.string.market_line_commit, technicalDisplay(git.commit)), style = DsType.small13, color = colors.labelSecondary)
        } else if (!registryNamed) {
            Text(
                if (defaultRegistry != null) stringResource(R.string.market_line_registry, technicalDisplay(defaultRegistry)) else stringResource(R.string.market_line_registry_own),
                style = DsType.small13,
                color = colors.labelSecondary,
            )
        }
        if (note.exempted) MarketNote(stringResource(R.string.market_compat_allowed))
        if (note.unverified) MarketNote(stringResource(R.string.market_unverified))
    }
}

// ---- The market sources ----

/**
 * The markets the harness reads. A market is just a list somebody published: the page says so, once, above the
 * list, and again on every entry that comes from one. Adding or removing a market is the harness's own setting
 * (the same one the web client edits).
 */
@Composable
internal fun PluginMarketSourcesPage(
    browser: PluginMarketBrowser,
    languages: List<String>,
    onClose: () -> Unit,
) {
    val colors = DsTheme.colors
    val sources = browser.sources.orEmpty()
    var url by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    // A source that was accepted clears the field; one that was refused keeps what was typed, with the reason.
    LaunchedEffect(browser.saving) {
        if (!browser.saving && submitted) { submitted = false; if (browser.sourceFailure == null) url = "" }
    }
    DisposableEffect(Unit) { onDispose { browser.clearSourceFailure() } }
    // What this page shows is what the host says now, not what it said when Discover was last read.
    LaunchedEffect(Unit) { browser.refreshSources() }
    FeaturePage(stringResource(R.string.market_sources_title), onClose) {
        MarketNote(stringResource(R.string.market_sources_trust))
        sources.forEach { source ->
            DsCard {
                Text(
                    technicalDisplay(source.title?.resolve(languages) ?: marketHost(source.url) ?: source.url),
                    style = DsType.std14Strong,
                    color = colors.labelPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(technicalDisplay(source.url), style = DsType.mono12, color = colors.labelTertiary)
                browser.sourceProblem(source)?.let { Text(technicalDisplay(it), style = DsType.small13, color = colors.error) }
                val showing = source.url == browser.selectedUrl
                if (showing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(12.dp), tint = colors.labelTertiary)
                        Text(stringResource(R.string.market_showing), style = DsType.small13, color = colors.labelSecondary)
                    }
                } else {
                    FeatureAction(stringResource(R.string.market_show), !browser.saving) { browser.select(source.url); onClose() }
                }
                FeatureAction(stringResource(R.string.common_remove), !browser.saving, DsButtonVariant.Danger) {
                    browser.saveSources(sources.map { it.url }.filter { it != source.url })
                }
            }
        }
        FeatureField(url, { url = it; browser.clearSourceFailure() }, R.string.market_source_url, !browser.saving, singleLine = true, technical = true)
        browser.sourceFailure?.let { Text(stringResource(marketSourceFailureText(it)), style = DsType.small13, color = colors.error) }
        DsButton(
            text = stringResource(R.string.market_source_add),
            onClick = { submitted = true; browser.saveSources(sources.map { it.url } + url.trim(), select = url.trim()) },
            enabled = !browser.saving && url.isNotBlank(),
            variant = DsButtonVariant.Primary,
            allowWrap = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (browser.saving) FeatureProgress()
    }
}
