package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.market.MarketIndexInfo
import com.labteto.dshmobile.core.market.MarketPage
import com.labteto.dshmobile.core.market.MarketQuery
import com.labteto.dshmobile.core.market.marketSearchArgs
import com.labteto.dshmobile.core.market.parseMarketIndexes
import com.labteto.dshmobile.core.market.parseMarketSearch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray

/** The error code the app gives an answer that is not a market answer at all (see `MarketFailure.Malformed`). */
const val MARKET_MALFORMED = "plugin-market/malformed"

private fun malformed(what: String): RpcResult<Nothing> =
    RpcResult.Err(RpcError(MARKET_MALFORMED, "The harness answered $what with something that is not a market answer"))

/**
 * The plugin market the harness reads for the Plugins page's Discover view (`packages/boot/plugin-market`).
 *
 * The harness fetches, validates and caches each index; these calls only ask it. Its Remote namespace
 * (`pluginMarket`) exists when the optional "custom plugin markets" bundle is on, so a host that does
 * not mount it answers `capability-unavailable` (see [classifyCapability]).
 */
suspend fun DshApiClient.marketIndexes(): RpcResult<List<MarketIndexInfo>> =
    when (val result = call("pluginMarket/listIndexes", JsonObject(emptyMap()), JsonElement.serializer())) {
        is RpcResult.Err -> result
        is RpcResult.Ok -> parseMarketIndexes(result.value)?.let { RpcResult.Ok(it) } ?: malformed("listIndexes")
    }

/** One page of an index's plugins, parsed and capped; see [marketSearchArgs] for what is sent. */
suspend fun DshApiClient.marketSearch(
    url: String,
    query: MarketQuery,
    offset: Int,
    limit: Int,
    fetchedAt: Long? = null,
    refresh: Boolean = false,
): RpcResult<MarketPage> =
    when (val result = call("pluginMarket/search", marketSearchArgs(url, query, offset, limit, fetchedAt, refresh), JsonElement.serializer())) {
        is RpcResult.Err -> result
        is RpcResult.Ok -> parseMarketSearch(result.value)?.let { RpcResult.Ok(it) } ?: malformed("search")
    }

/** Replace the configured index sources (the whole list, in display order). The host writes it through its settings. */
suspend fun DshApiClient.marketSetIndexes(urls: List<String>): RpcResult<Unit> =
    when (val result = call("pluginMarket/setIndexes", buildJsonObject { put("urls", JsonArray(urls.map { JsonPrimitive(it) })) }, JsonElement.serializer())) {
        is RpcResult.Err -> result
        is RpcResult.Ok -> RpcResult.Ok(Unit)
    }
