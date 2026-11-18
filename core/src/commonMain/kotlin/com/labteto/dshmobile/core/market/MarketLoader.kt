package com.labteto.dshmobile.core.market

import com.labteto.dshmobile.core.wire.RpcResult

/** One `pluginMarket/search` page request: where to start, how many, and which cached document to continue. */
typealias MarketPageFetch = suspend (offset: Int, limit: Int, fetchedAt: Long?, refresh: Boolean) -> RpcResult<MarketPage>

/** What loading a listing ended in. */
sealed interface MarketLoad {
    data class Loaded(val page: MarketPage) : MarketLoad

    /** The host refused or could not answer; the failure says why. */
    data class Failed(val failure: MarketFailure) : MarketLoad

    /** The index was replaced on the host twice while its pages were being read, so the pages do not belong together. */
    data object Changed : MarketLoad
}

private fun MarketPage.append(next: MarketPage, cap: Int): MarketPage {
    val have = entries.mapTo(HashSet()) { it.name }
    val added = next.entries.filter { have.add(it.name) }
    return next.copy(entries = (entries + added).take(cap), dropped = dropped + next.dropped)
}

/**
 * Reads the first [count] results of a query in pages the host accepts. Later pages name the first page's
 * `fetchedAt`, so they continue the same document; when the host answers from a replaced one, the read starts
 * over once, and a second replacement is [MarketLoad.Changed] (the web client does the same). [count] is held to
 * [MarketLimits.MAX_LOADED]. Entries a host repeats are shown once.
 */
suspend fun loadMarketEntries(count: Int, refresh: Boolean, fetch: MarketPageFetch): MarketLoad {
    val wanted = count.coerceIn(1, MarketLimits.MAX_LOADED)
    var value: MarketPage? = null
    var restarted = false
    var requests = 0
    while (value == null || (value.entries.size < wanted && value.nextOffset != null)) {
        // A host that keeps answering with nothing new is stopped here instead of being asked forever.
        if (++requests > MAX_REQUESTS) {
            return value?.let { MarketLoad.Loaded(it.copy(nextOffset = null)) } ?: MarketLoad.Failed(MarketFailure.Malformed)
        }
        val offset = value?.entries?.size ?: 0
        // The offset the host should continue from is the one it gave, not our own count, which may have dropped entries.
        val start = value?.nextOffset ?: 0
        val limit = minOf(wanted - offset, MarketLimits.MAX_PAGE).coerceAtLeast(1)
        val answer = when (val result = fetch(start, limit, value?.fetchedAt, refresh && value == null && !restarted)) {
            is RpcResult.Err -> return MarketLoad.Failed(result.error.toMarketFailure())
            is RpcResult.Ok -> result.value
        }
        val current = value
        value = when {
            current == null -> answer.copy(entries = answer.entries.distinctBy { it.name }.take(wanted))
            answer.fetchedAt == current.fetchedAt -> current.append(answer, wanted)
            restarted -> return MarketLoad.Changed
            else -> { restarted = true; null }
        }
        // A page that adds nothing and has no end would never finish.
        if (value != null && current != null && value.entries.size == current.entries.size && value.nextOffset != null && value.nextOffset == current.nextOffset) {
            return MarketLoad.Loaded(value.copy(nextOffset = null))
        }
    }
    return MarketLoad.Loaded(checkNotNull(value))
}

/** The most requests one listing read makes: [MarketLimits.MAX_LOADED] entries at 50, with room for one restart. */
private const val MAX_REQUESTS = 14

/** The page after the ones in [current]; a replaced document reloads everything from the start with one page more. */
suspend fun loadMoreMarketEntries(current: MarketPage, fetch: MarketPageFetch): MarketLoad {
    val start = current.nextOffset ?: return MarketLoad.Loaded(current)
    if (current.entries.size >= MarketLimits.MAX_LOADED) return MarketLoad.Loaded(current.copy(nextOffset = null))
    val limit = minOf(MarketLimits.PAGE_SIZE, MarketLimits.MAX_LOADED - current.entries.size)
    val answer = when (val result = fetch(start, limit, current.fetchedAt, false)) {
        is RpcResult.Err -> return MarketLoad.Failed(result.error.toMarketFailure())
        is RpcResult.Ok -> result.value
    }
    if (answer.fetchedAt != current.fetchedAt) return loadMarketEntries(current.entries.size + MarketLimits.PAGE_SIZE, false, fetch)
    val merged = current.append(answer, MarketLimits.MAX_LOADED)
    val stuck = merged.entries.size == current.entries.size && merged.nextOffset == current.nextOffset
    val atCap = merged.entries.size >= MarketLimits.MAX_LOADED
    return MarketLoad.Loaded(if (stuck || atCap) merged.copy(nextOffset = null) else merged)
}
