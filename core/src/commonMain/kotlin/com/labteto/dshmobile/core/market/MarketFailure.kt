package com.labteto.dshmobile.core.market

import com.labteto.dshmobile.core.wire.RpcError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Why a market call (a `pluginMarket` method) failed, in the host's own vocabulary (`packages/boot/plugin-market/src/types.ts`).
 * [reason] and [path] are the host's words about a source it could not read; they are third-party text
 * (an index owner's server wrote some of it), so they are cleaned and capped.
 */
sealed interface MarketFailure {
    /** The host could not fetch the index: network, timeout, redirect, a non-2xx answer, or a body over its limit. */
    data class Fetch(val reason: String?) : MarketFailure

    /** The host fetched it and it is not a valid format-1 index; [path] names the offending JSON value. */
    data class InvalidIndex(val path: String?, val reason: String?) : MarketFailure

    /** The source is not one the harness has configured (it was removed in the meantime). */
    data object UnknownIndex : MarketFailure

    /** A source URL was refused: `duplicate`, `scheme`, `not-absolute`, `credentials-or-fragment`, or `configured`. */
    data class InvalidUrl(val reason: String?) : MarketFailure

    /** Sources cannot be saved without the harness's settings service. */
    data object SettingsUnavailable : MarketFailure

    /** The harness does not offer the market at all (an older harness, or the market bundle is off). */
    data object Unavailable : MarketFailure

    /** The host answered something that is not a market answer. */
    data object Malformed : MarketFailure

    /** The index was replaced on the host while its pages were being read (see [MarketLoad.Changed]). */
    data object Changed : MarketFailure

    data class Other(val code: String, val message: String?) : MarketFailure
}

private fun JsonObject.detail(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { cleanMarketText(it, MarketLimits.REASON) }

fun RpcError.toMarketFailure(): MarketFailure {
    val details = details as? JsonObject
    return when (code) {
        "plugin-market/fetch-failed" -> MarketFailure.Fetch(details?.detail("reason"))
        "plugin-market/invalid-index" -> MarketFailure.InvalidIndex(details?.detail("path"), details?.detail("reason"))
        "plugin-market/unknown-index" -> MarketFailure.UnknownIndex
        "plugin-market/invalid-index-url" -> MarketFailure.InvalidUrl(details?.detail("reason"))
        "plugin-market/settings-unavailable" -> MarketFailure.SettingsUnavailable
        "capability-unavailable" -> MarketFailure.Unavailable
        "plugin-market/malformed" -> MarketFailure.Malformed
        else -> MarketFailure.Other(cleanMarketText(code, MarketLimits.REASON) ?: "error", cleanMarketText(message, MarketLimits.REASON))
    }
}
