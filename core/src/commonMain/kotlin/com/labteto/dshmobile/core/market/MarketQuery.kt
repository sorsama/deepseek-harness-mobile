package com.labteto.dshmobile.core.market

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the Discover view asks the harness for: search text, one category, and labels that must all match.
 * The host does the matching (fuzzy over names, authors, keywords and every language of the texts), the
 * filtering and the paging; the app only says what it wants and never filters a page itself.
 *
 * Order is the index's own, or the best match first while there is text (what the web client does).
 */
data class MarketQuery(
    val text: String = "",
    val category: String? = null,
    val labels: List<String> = emptyList(),
) {
    /** Nothing narrows the market: no text, no category, no label. */
    val isUnfiltered: Boolean get() = text.isBlank() && category == null && labels.isEmpty()

    fun withText(value: String): MarketQuery = copy(text = value.take(MarketLimits.SEARCH_TEXT))

    /** A category chip: choosing the chosen one again clears it. */
    fun toggleCategory(id: String): MarketQuery = copy(category = if (category == id) null else id)

    /** A label chip: labels add up, and an entry must carry every chosen one. */
    fun toggleLabel(id: String): MarketQuery = copy(labels = if (id in labels) labels - id else labels + id)

    fun withoutFilters(): MarketQuery = copy(category = null, labels = emptyList())

    /**
     * The query without a category or label the market does not (or no longer) declare, so every active chip
     * is one the person can see. The same query when nothing had to go.
     */
    fun declaredIn(header: MarketHeader): MarketQuery {
        val categories = header.categories.mapTo(HashSet()) { it.id }
        val declared = header.labels.mapTo(HashSet()) { it.id }
        val keptCategory = category?.takeIf { it in categories }
        val keptLabels = labels.filter { it in declared }
        return if (keptCategory == category && keptLabels.size == labels.size) this else copy(category = keptCategory, labels = keptLabels)
    }
}

/** The `pluginMarket/search` arguments for one page (`url` and `query`, the host method's parameter names). */
fun marketSearchArgs(
    url: String,
    query: MarketQuery,
    offset: Int,
    limit: Int,
    fetchedAt: Long? = null,
    refresh: Boolean = false,
): JsonObject = buildJsonObject {
    put("url", url)
    put("query", buildJsonObject {
        val text = query.text.trim()
        if (text.isNotEmpty()) put("text", text.take(MarketLimits.SEARCH_TEXT))
        query.category?.let { put("category", it) }
        if (query.labels.isNotEmpty()) put("labels", JsonArray(query.labels.map { JsonPrimitive(it) }))
        put("sort", buildJsonObject { put("kind", if (text.isEmpty()) "index" else "relevance") })
        put("offset", offset.coerceAtLeast(0))
        put("limit", limit.coerceIn(1, MarketLimits.MAX_PAGE))
        fetchedAt?.let { put("fetchedAt", it) }
        if (refresh) put("refresh", true)
    })
}
