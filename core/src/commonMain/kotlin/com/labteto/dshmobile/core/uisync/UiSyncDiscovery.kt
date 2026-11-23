package com.labteto.dshmobile.core.uisync

import com.labteto.dshmobile.core.wire.dto.PluginInventoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Discovery: filling the item store when no page has.
 *
 * The companion's pages record their plugin items in the `ui-sync` row (feature `item-store`), but
 * only once a page has loaded. On a harness where nobody opened the web client since the companion
 * or a new plugin went in, the store is empty or misses that plugin: the phone then has nothing to
 * show and never loads a page that would fill it. A discovery load breaks that circle: the app loads
 * the add-on page once, out of sight and out of reach of touch, lets it record its items, and closes
 * it again unless the phone now has something to draw.
 */

/** npm name of the companion itself: its own registrations are scaffolding, never items. */
const val UI_SYNC_COMPANION_PACKAGE: String = "dsh-ui-sync"

/** npm scope of the harness's own packages, whose items are never stored (the companion's `CORE_SCOPE`). */
const val UI_SYNC_CORE_SCOPE: String = "@deepseek-ai/"

/** Why the item store looks incomplete. */
enum class UiSyncDiscoveryReason {
    /** The store has no item at all. */
    EMPTY_STORE,

    /** An enabled third-party plugin has nothing in the store yet. */
    NEW_PACKAGES,
}

/**
 * The item store looks incomplete for [reason]. [fingerprint] names the set of enabled third-party
 * plugins it was judged against ([UI_SYNC_UNKNOWN_PLUGINS] when the plugin list could not be read),
 * so a discovery runs once per plugin set, not on every start.
 */
data class UiSyncDiscoveryNeed(val reason: UiSyncDiscoveryReason, val fingerprint: String)

/** The fingerprint of a plugin set the app could not read (a relay that keeps the plugin list to itself). */
const val UI_SYNC_UNKNOWN_PLUGINS: String = "?"

/**
 * The npm package a plugin list row loads, for comparing with the item store's `package`; null for
 * a row that is the harness's own (a `cordis:` built-in, an `@deepseek-ai/` package) or the
 * companion itself. A row named by a path or a file URL (a plugin loaded from a folder) has no
 * package name to compare, so it reads as itself with a `?` in front: never in the store, so it
 * counts as unknown, and a discovery once per plugin set finds out what it adds.
 */
fun uiSyncInventoryPackage(moduleName: String): String? {
    val name = moduleName.trim()
    if (name.isEmpty() || name.startsWith("cordis:")) return null
    val pathLike = name.startsWith(".") || name.startsWith("/") || name.startsWith("\\") ||
        name.startsWith("file:") || (name.length > 1 && name[1] == ':')
    if (pathLike) return "?$name"
    // A subpath row (`pkg/gateway`, `@scope/pkg/gateway`) belongs to its package.
    val parts = name.split('/')
    val pkg = if (name.startsWith("@")) parts.take(2).joinToString("/") else parts.first()
    if (pkg.startsWith(UI_SYNC_CORE_SCOPE) || pkg == UI_SYNC_COMPANION_PACKAGE) return null
    return pkg
}

/**
 * The enabled third-party plugin packages of a plugin list, sorted: the ones that could register
 * items the store does not know yet.
 */
fun uiSyncThirdPartyPackages(inventory: List<PluginInventoryEntry>): List<String> =
    inventory.asSequence().filter { it.enabled }.mapNotNull { uiSyncInventoryPackage(it.moduleName) }.distinct().sorted().toList()

/** The fingerprint of a plugin list (null when it could not be read): its third-party packages, one per line. */
fun uiSyncPluginFingerprint(inventory: List<PluginInventoryEntry>?): String =
    fingerprintOf(inventory?.let { uiSyncThirdPartyPackages(it) })

private fun fingerprintOf(packages: List<String>?): String = packages?.joinToString("\n") ?: UI_SYNC_UNKNOWN_PLUGINS

/**
 * Whether the item store [store] looks incomplete, judged against the harness's plugin list
 * [inventory] (null when it could not be read); null when it looks complete.
 *
 * - A companion without an item store (0.1.0) needs nothing: the layer loads a page for it anyway.
 * - An empty store needs a look, unless the plugin list says no third-party plugin is enabled (then
 *   nothing could fill it; "Find add-ons" can still look by hand).
 * - A store that has items needs one when an enabled third-party plugin has none in it. Plugins with
 *   no web part never get any, which is why a discovery is limited to one per plugin set (see
 *   [uiSyncAutoDiscoveryAllowed]).
 */
fun uiSyncDiscoveryNeed(store: UiSyncItemStore, inventory: List<PluginInventoryEntry>?): UiSyncDiscoveryNeed? {
    if (!store.hasStore) return null
    val packages = inventory?.let { uiSyncThirdPartyPackages(it) }
    val fingerprint = fingerprintOf(packages)
    if (store.items.isEmpty()) {
        if (packages != null && packages.isEmpty()) return null
        return UiSyncDiscoveryNeed(UiSyncDiscoveryReason.EMPTY_STORE, fingerprint)
    }
    if (packages == null) return null
    val stored = store.items.mapTo(HashSet()) { it.pkg }
    if (packages.all { it in stored }) return null
    return UiSyncDiscoveryNeed(UiSyncDiscoveryReason.NEW_PACKAGES, fingerprint)
}

/** How a discovery load ended. */
enum class UiSyncDiscoveryOutcome {
    /** The store has items now (the page recorded them, or they were there). */
    FOUND,

    /** The page loaded and reported no item, and the store is still empty. */
    NOTHING,

    /** The page never came up, or what it found never reached the store. */
    FAILED,
}

/** The last discovery load for one host: which plugin set it ran for, when, and how it ended. */
data class UiSyncDiscoveryRecord(val fingerprint: String, val at: Long, val outcome: UiSyncDiscoveryOutcome) {
    /** One line for a preferences file: outcome, time, then the fingerprint (which may hold anything). */
    fun encode(): String = "${outcome.name}\t$at\t$fingerprint"

    companion object {
        fun decode(text: String?): UiSyncDiscoveryRecord? {
            val parts = text?.split('\t', limit = 3) ?: return null
            if (parts.size != 3) return null
            val outcome = UiSyncDiscoveryOutcome.entries.firstOrNull { it.name == parts[0] } ?: return null
            val at = parts[1].toLongOrNull() ?: return null
            return UiSyncDiscoveryRecord(parts[2], at, outcome)
        }
    }
}

/** A failed discovery for an unchanged plugin set is tried again after this long. */
const val UI_SYNC_DISCOVERY_FAILED_RETRY_MS: Long = 30L * 60 * 1000

/** With the plugin list unreadable, an unchanged empty store is looked at again after this long. */
const val UI_SYNC_DISCOVERY_UNKNOWN_RETRY_MS: Long = 24L * 60 * 60 * 1000

/** Two discoveries on their own never start closer than this, even for a plugin set that keeps changing. */
const val UI_SYNC_DISCOVERY_MIN_GAP_MS: Long = 30L * 1000

/**
 * The rate limit for discoveries the app starts on its own (a person's "Find add-ons" is never
 * limited): once per plugin set and host, not once per app start.
 *
 * - Never for this host: go.
 * - The plugin set changed since the last one: go, unless the last started less than
 *   [UI_SYNC_DISCOVERY_MIN_GAP_MS] ago.
 * - Same plugin set: only after a failure, [UI_SYNC_DISCOVERY_FAILED_RETRY_MS] later; when the last
 *   one found items and the store is empty again (the harness was set up afresh at the same
 *   address, or someone cleared it), after the short gap; and when the plugin list cannot be read,
 *   so a change cannot be seen, once a day at most.
 */
fun uiSyncAutoDiscoveryAllowed(record: UiSyncDiscoveryRecord?, need: UiSyncDiscoveryNeed, now: Long): Boolean {
    if (record == null) return true
    val since = now - record.at
    // A clock that went backwards says nothing about how long ago it was: allow it, once.
    if (since < 0) return true
    if (record.fingerprint != need.fingerprint) return since >= UI_SYNC_DISCOVERY_MIN_GAP_MS
    if (record.outcome == UiSyncDiscoveryOutcome.FAILED) return since >= UI_SYNC_DISCOVERY_FAILED_RETRY_MS
    if (record.outcome == UiSyncDiscoveryOutcome.FOUND && need.reason == UiSyncDiscoveryReason.EMPTY_STORE) {
        return since >= UI_SYNC_DISCOVERY_MIN_GAP_MS
    }
    if (need.fingerprint == UI_SYNC_UNKNOWN_PLUGINS) return since >= UI_SYNC_DISCOVERY_UNKNOWN_RETRY_MS
    return false
}

/**
 * Whether the Add-ons page should offer to look for missing add-ons: the store looks incomplete
 * ([need]) and no discovery has looked at this plugin set yet, or the one that did failed.
 */
fun uiSyncDiscoveryStale(need: UiSyncDiscoveryNeed?, record: UiSyncDiscoveryRecord?): Boolean {
    if (need == null) return false
    if (record == null || record.fingerprint != need.fingerprint) return true
    return record.outcome == UiSyncDiscoveryOutcome.FAILED
}

/** Who asked for a discovery load. */
enum class UiSyncDiscoveryTrigger {
    /** The app, because the store looked incomplete ([uiSyncDiscoveryNeed]). */
    AUTO,

    /** A person, with "Find add-ons". */
    MANUAL,
}

/** Where a discovery load stands. */
sealed interface UiSyncDiscoveryPhase {
    data object Idle : UiSyncDiscoveryPhase

    /**
     * A page is loading for run [run]: [readyAt] once it said it is in phone mode, [pageItems] the
     * item ids it reported last (null until it reported any) and [itemsAt] when.
     */
    data class Running(
        val run: Long,
        val trigger: UiSyncDiscoveryTrigger,
        val startedAt: Long,
        val readyAt: Long? = null,
        val pageItems: Set<String>? = null,
        val itemsAt: Long? = null,
    ) : UiSyncDiscoveryPhase

    data class Done(
        val run: Long,
        val trigger: UiSyncDiscoveryTrigger,
        val outcome: UiSyncDiscoveryOutcome,
        val at: Long,
    ) : UiSyncDiscoveryPhase
}

/** How long a discovery load waits for each step; see [UiSyncDiscovery.check]. */
data class UiSyncDiscoveryTiming(
    /** From the start until the page says it is in phone mode (the whole web client boots, maybe through a relay). */
    val loadTimeoutMs: Long = 30_000,
    /** From ready until the page's items are in the store. */
    val itemsTimeoutMs: Long = 15_000,
    /** A page that reported no item at all, and nothing since, for this long has none. */
    val quietMs: Long = 5_000,
    /** Items in the store count only once the page's list held still this long (a plugin may register a second one late). */
    val settleMs: Long = 2_000,
)

/**
 * The discovery load's state machine, without a device: the web layer feeds it what the page says
 * and asks [check] what that means, with the time passed in.
 *
 * One per app process (the session store owns it), shared by the web layer that runs the page and
 * the Add-ons page that shows its progress. A run ends:
 *
 * - [UiSyncDiscoveryOutcome.FOUND] once every item the page reported is in the store and the page's
 *   list held still for [UiSyncDiscoveryTiming.settleMs], or when the page reported none and the
 *   store has items anyway;
 * - [UiSyncDiscoveryOutcome.NOTHING] once the page reported an empty list and stayed quiet for
 *   [UiSyncDiscoveryTiming.quietMs] with the store still empty;
 * - [UiSyncDiscoveryOutcome.FAILED] when the page never came up, failed, or what it reported never
 *   reached the store in time.
 */
class UiSyncDiscovery(private val timing: UiSyncDiscoveryTiming = UiSyncDiscoveryTiming()) {
    private val _state = MutableStateFlow<UiSyncDiscoveryPhase>(UiSyncDiscoveryPhase.Idle)
    val state: StateFlow<UiSyncDiscoveryPhase> = _state.asStateFlow()

    private var runs = 0L

    val running: Boolean get() = _state.value is UiSyncDiscoveryPhase.Running

    /**
     * Start a run, or join the one in progress (a person's request makes it theirs, so its result is
     * shown). Returns the run's number.
     */
    fun start(trigger: UiSyncDiscoveryTrigger, now: Long): Long {
        val current = _state.value
        if (current is UiSyncDiscoveryPhase.Running) {
            if (trigger == UiSyncDiscoveryTrigger.MANUAL && current.trigger != trigger) _state.value = current.copy(trigger = trigger)
            return current.run
        }
        val run = ++runs
        _state.value = UiSyncDiscoveryPhase.Running(run, trigger, startedAt = now)
        return run
    }

    /**
     * The page said it is in phone mode. It says so again with every `host/hello` snapshot and after a
     * reload; the list it reported before no longer counts, but the clock keeps running from the first.
     */
    fun onPageReady(now: Long) = update { it.copy(readyAt = it.readyAt ?: now, pageItems = null, itemsAt = null) }

    /** The page reported its items. */
    fun onPageItems(ids: Set<String>, now: Long) = update {
        if (it.readyAt == null || it.pageItems == ids) it else it.copy(pageItems = ids, itemsAt = now)
    }

    /** The page cannot come up (no web view, no sign-in, its renderer died): the run fails now. */
    fun onPageFailed(now: Long) {
        val current = _state.value as? UiSyncDiscoveryPhase.Running ?: return
        finish(current, UiSyncDiscoveryOutcome.FAILED, now)
    }

    /**
     * Decide whether the run is over, given the item store as the app holds it now ([store], null
     * while unknown). Returns the phase after the check.
     */
    fun check(now: Long, store: UiSyncItemStore?): UiSyncDiscoveryPhase {
        val run = _state.value as? UiSyncDiscoveryPhase.Running ?: return _state.value
        val ready = run.readyAt
        if (ready == null) {
            if (now - run.startedAt >= timing.loadTimeoutMs) finish(run, UiSyncDiscoveryOutcome.FAILED, now)
            return _state.value
        }
        val page = run.pageItems
        val stored = store?.items?.mapTo(HashSet()) { it.id }
        val lastChange = run.itemsAt ?: ready
        when {
            page != null && page.isNotEmpty() && stored != null && stored.containsAll(page) && now - lastChange >= timing.settleMs ->
                finish(run, UiSyncDiscoveryOutcome.FOUND, now)
            page != null && page.isEmpty() && stored != null && now - lastChange >= timing.quietMs ->
                finish(run, if (stored.isEmpty()) UiSyncDiscoveryOutcome.NOTHING else UiSyncDiscoveryOutcome.FOUND, now)
            now - ready >= timing.itemsTimeoutMs ->
                // What the page found never reached the store (it could not write it), or it never answered.
                finish(run, if (page.isNullOrEmpty() && !stored.isNullOrEmpty()) UiSyncDiscoveryOutcome.FOUND else UiSyncDiscoveryOutcome.FAILED, now)
        }
        return _state.value
    }

    /** The host changed or went away: nothing of the run applies any more. */
    fun cancel() {
        _state.value = UiSyncDiscoveryPhase.Idle
    }

    private fun finish(run: UiSyncDiscoveryPhase.Running, outcome: UiSyncDiscoveryOutcome, now: Long) {
        if (_state.value != run) return
        _state.value = UiSyncDiscoveryPhase.Done(run.run, run.trigger, outcome, now)
    }

    private inline fun update(change: (UiSyncDiscoveryPhase.Running) -> UiSyncDiscoveryPhase.Running) {
        val current = _state.value as? UiSyncDiscoveryPhase.Running ?: return
        _state.value = change(current)
    }
}
