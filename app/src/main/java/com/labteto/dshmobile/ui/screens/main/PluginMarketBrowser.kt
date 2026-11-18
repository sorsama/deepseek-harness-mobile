package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.labteto.dshmobile.core.market.MarketCheck
import com.labteto.dshmobile.core.market.MarketEntry
import com.labteto.dshmobile.core.market.MarketFailure
import com.labteto.dshmobile.core.market.MarketIndexInfo
import com.labteto.dshmobile.core.market.MarketLimits
import com.labteto.dshmobile.core.market.MarketLoad
import com.labteto.dshmobile.core.market.MarketPage
import com.labteto.dshmobile.core.market.MarketQuery
import com.labteto.dshmobile.core.market.MarketVersion
import com.labteto.dshmobile.core.market.loadMarketEntries
import com.labteto.dshmobile.core.market.loadMoreMarketEntries
import com.labteto.dshmobile.core.market.marketCheck
import com.labteto.dshmobile.core.market.toMarketFailure
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.data.HarnessFeature
import com.labteto.dshmobile.data.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where a listing read stands. [MORE] is the next page being added to a list that is already showing. */
internal enum class MarketStatus { IDLE, LOADING, MORE, READY, ERROR }

/** The check that runs before an install is offered for confirmation (the harness's own look at the package). */
internal sealed interface MarketCheckState {
    data object Idle : MarketCheckState
    data class Checking(val name: String) : MarketCheckState
    data class Problem(val name: String, val check: MarketCheck) : MarketCheckState
    data class Failed(val name: String, val message: String) : MarketCheckState
}

/**
 * Everything the Discover view holds for one host: its market sources, the one being shown, what is asked of it,
 * and what has been read. The harness fetches and searches; this only asks (see `core/market`).
 *
 * Results stay on screen while a new read runs or fails, like the web client. Nothing loads until [ensureLoaded]:
 * reading a market makes the harness fetch an index from the internet, so it waits for the person to open Discover.
 */
@Stable
internal class PluginMarketBrowser(
    private val scope: CoroutineScope,
    private val store: SessionStore,
    private val host: String?,
) {
    var sources by mutableStateOf<List<MarketIndexInfo>?>(null); private set
    var selectedUrl by mutableStateOf<String?>(null); private set
    var query by mutableStateOf(MarketQuery()); private set
    var page by mutableStateOf<MarketPage?>(null); private set

    /** The query [page] answers: it differs from [query] while a new one runs or after it failed. */
    var answered by mutableStateOf<MarketQuery?>(null); private set
    var status by mutableStateOf(MarketStatus.IDLE); private set
    var failure by mutableStateOf<MarketFailure?>(null); private set

    /** The market [failure] is about, or null when it is about none (the list of markets itself could not be read). */
    var failureUrl by mutableStateOf<String?>(null); private set

    /**
     * What this session has seen of each market since the host listed it. The host keeps the last error it recorded for a
     * market it was not asked about again, so a market that has loaded since is no longer in error whatever the host says.
     */
    private var loadedUrls by mutableStateOf<Set<String>>(emptySet())
    private var failedUrls by mutableStateOf<Map<String, MarketFailure>>(emptyMap())
    var saving by mutableStateOf(false); private set
    var sourceFailure by mutableStateOf<MarketFailure?>(null); private set
    var check by mutableStateOf<MarketCheckState>(MarketCheckState.Idle); private set

    private var started = false
    private var read = 0
    private var retryAction: () -> Unit = {}

    private fun api(): DshApiClient? = store.apiForHost(host)

    /**
     * One call to the harness. Only [probe] calls (the source list, which never fails for any reason but a missing
     * market) tell the app whether the market exists: a search that fails because an index could not be fetched is a
     * business answer, and [SessionStore.featureCall] would show it as a connection error.
     */
    private suspend fun <T> market(probe: Boolean = false, block: suspend (DshApiClient) -> RpcResult<T>): RpcResult<T> {
        val api = api() ?: return RpcResult.Err(RpcError("offline", "Offline"))
        return try {
            if (probe) store.featureCall(HarnessFeature.PLUGIN_MARKET, api) { block(api) } else block(api)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RpcResult.Err(RpcError("error", e.message ?: "error"))
        }
    }

    /** The first read of the sources and of the market they name, once, when Discover is first shown. */
    fun ensureLoaded() {
        if (started) return
        started = true
        loadSources()
    }

    /** The market changed on the host (a plugin was installed or removed, or the sources changed): read it again, quietly. */
    fun invalidate() {
        if (!started) return
        loadSources()
    }

    /** The refresh button: fetch the index again, not the host's cached copy. */
    fun refresh() {
        if (!started) { ensureLoaded(); return }
        val url = selectedUrl ?: return loadSources()
        load(url, query, count = (page?.entries?.size ?: 0).coerceAtLeast(MarketLimits.PAGE_SIZE), refresh = true)
    }

    /** Retry the read that failed, and read the list of markets again so what the Markets page says is current. */
    fun retry() {
        retryAction()
        refreshSources()
    }

    /**
     * Read the list of markets again and nothing else: the host answers from what it has, it never fetches an index for
     * this. Called when the Markets page opens, after a retry and after a market loads, so a title or an error that changed
     * on the host does not stay as the last read left it.
     */
    fun refreshSources() {
        scope.launch {
            val result = market { it.marketIndexes() }
            if (result is RpcResult.Ok) sources = result.value
        }
    }

    /** What the Markets page says is wrong with [source], or null: the host's words, unless the market has loaded since. */
    fun sourceProblem(source: MarketIndexInfo): String? = marketSourceProblem(source.error, source.url in loadedUrls, failedUrls[source.url])

    private fun loadSources(select: String? = null) {
        scope.launch {
            val id = ++read
            if (status == MarketStatus.IDLE) status = MarketStatus.LOADING
            when (val result = market(probe = true) { it.marketIndexes() }) {
                is RpcResult.Err -> {
                    if (id != read) return@launch
                    failure = result.error.toMarketFailure()
                    failureUrl = null
                    status = MarketStatus.ERROR
                    retryAction = { loadSources() }
                }
                is RpcResult.Ok -> {
                    if (id != read) return@launch
                    val list = result.value
                    sources = list
                    val previous = selectedUrl
                    val next = select?.takeIf { url -> list.any { it.url == url } }
                        ?: previous?.takeIf { url -> list.any { it.url == url } }
                        ?: list.firstOrNull()?.url
                    selectedUrl = next
                    if (next == null) {
                        page = null; answered = null; failure = null; failureUrl = null; status = MarketStatus.READY
                    } else if (next != previous || page == null) {
                        query = MarketQuery(query.text)
                        // Another market is now shown: nothing of the one before may stay under its name (or under an error of the new one).
                        if (next != previous) { page = null; answered = null }
                        load(next, query, MarketLimits.PAGE_SIZE, refresh = false)
                    } else {
                        // The same market: recount what is installed, from the host's cached document.
                        load(next, query, (page?.entries?.size ?: 0).coerceAtLeast(MarketLimits.PAGE_SIZE), refresh = false)
                    }
                }
            }
        }
    }

    /** Ask for [count] entries of [url] with [asked]; the list on screen stays until the answer is in. */
    private fun load(url: String, asked: MarketQuery, count: Int, refresh: Boolean) {
        val id = ++read
        status = MarketStatus.LOADING
        failure = null
        failureUrl = null
        scope.launch {
            val outcome = loadMarketEntries(count, refresh) { offset, limit, fetchedAt, again ->
                market { it.marketSearch(url, asked, offset, limit, fetchedAt, again) }
            }
            if (id != read) return@launch
            when (outcome) {
                is MarketLoad.Loaded -> {
                    val declared = asked.declaredIn(outcome.page.header)
                    if (declared != asked) {
                        query = declared
                        load(url, declared, count, refresh = false)
                    } else {
                        page = outcome.page; answered = asked; failure = null; failureUrl = null; status = MarketStatus.READY
                        loaded(url)
                    }
                }
                is MarketLoad.Failed -> fail(url, outcome.failure) { load(url, asked, count, refresh) }
                MarketLoad.Changed -> fail(url, MarketFailure.Changed) { load(url, asked, count, refresh) }
            }
        }
    }

    private fun fail(url: String, reason: MarketFailure, again: () -> Unit) {
        failure = reason
        failureUrl = url
        status = MarketStatus.ERROR
        retryAction = again
        loadedUrls = loadedUrls - url
        failedUrls = failedUrls + (url to reason)
    }

    /** A market was read: its error, if the host still lists one, is over; and its title may be known now. */
    private fun loaded(url: String) {
        loadedUrls = loadedUrls + url
        failedUrls = failedUrls - url
        if (sources?.firstOrNull { it.url == url }?.let { it.title == null || it.error != null } == true) refreshSources()
    }

    /** The search text, after the typing pause. Blank text asks for the market in its own order. */
    fun setText(text: String) {
        val next = query.withText(text)
        if (next == query && status != MarketStatus.ERROR) return
        // Text typed before the first market is read is kept and used for that read.
        query = next
        val url = selectedUrl ?: return
        load(url, next, MarketLimits.PAGE_SIZE, refresh = false)
    }

    fun applyQuery(next: MarketQuery) {
        val url = selectedUrl ?: return
        query = next
        load(url, next, MarketLimits.PAGE_SIZE, refresh = false)
    }

    fun select(url: String) {
        if (url == selectedUrl) return
        selectedUrl = url
        query = MarketQuery(query.text)
        page = null; answered = null
        load(url, query, MarketLimits.PAGE_SIZE, refresh = false)
    }

    /** "Show more": the next page of the same document. */
    fun more() {
        val current = page ?: return
        val url = selectedUrl ?: return
        // A failed next page leaves the list as it was, still answering its query, so Retry can ask for the page again.
        if (status == MarketStatus.LOADING || status == MarketStatus.MORE || current.nextOffset == null || answered != query) return
        val id = ++read
        status = MarketStatus.MORE
        failure = null
        failureUrl = null
        scope.launch {
            val asked = query
            val outcome = loadMoreMarketEntries(current) { offset, limit, fetchedAt, again ->
                market { it.marketSearch(url, asked, offset, limit, fetchedAt, again) }
            }
            if (id != read) return@launch
            when (outcome) {
                is MarketLoad.Loaded -> { page = outcome.page; status = MarketStatus.READY }
                is MarketLoad.Failed -> fail(url, outcome.failure) { more() }
                MarketLoad.Changed -> fail(url, MarketFailure.Changed) { more() }
            }
        }
    }

    /** Replace the sources (the whole list, in order). [select] is the URL to show afterwards, for a source just added. */
    fun saveSources(urls: List<String>, select: String? = null) {
        if (saving) return
        saving = true
        sourceFailure = null
        scope.launch {
            when (val result = market { it.marketSetIndexes(urls) }) {
                is RpcResult.Err -> sourceFailure = result.error.toMarketFailure()
                is RpcResult.Ok -> { sourceFailure = null; loadSources(select) }
            }
            saving = false
        }
    }

    fun clearSourceFailure() { sourceFailure = null }

    // ---- install: the harness looks at the package first, then the person confirms ----

    fun dismissCheck() { check = MarketCheckState.Idle }

    /**
     * Start an install of [version]. A package that is not installed is inspected first (a market is a claim; the harness
     * reads the real package); [onConfirm] gets the subject for the existing confirmation only when nothing disagrees.
     * An update skips the check: the harness refuses to inspect what it already has, and re-reads the package at install.
     */
    fun beginInstall(entry: MarketEntry, version: MarketVersion, subject: PluginInstallSubject, update: Boolean, onConfirm: (PluginInstallSubject) -> Unit) {
        if (check is MarketCheckState.Checking) return
        if (update) { check = MarketCheckState.Idle; onConfirm(subject); return }
        check = MarketCheckState.Checking(entry.name)
        scope.launch {
            val api = api()
            if (api == null) { check = MarketCheckState.Failed(entry.name, "Offline"); return@launch }
            try {
                val inspection = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.inspectPlugin(version.installSpec, version.installRegistry) }.featureValue()
                when (val verdict = marketCheck(entry, version, inspection)) {
                    MarketCheck.Proceed -> { check = MarketCheckState.Idle; onConfirm(subject) }
                    else -> check = MarketCheckState.Problem(entry.name, verdict)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                check = MarketCheckState.Failed(entry.name, e.message ?: "error")
            }
        }
    }
}
