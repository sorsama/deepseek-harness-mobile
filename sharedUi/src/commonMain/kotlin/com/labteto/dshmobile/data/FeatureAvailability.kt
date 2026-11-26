package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.classifyCapability
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [PLUGIN_MARKET] is the optional "custom plugin markets" bundle: its `pluginMarket` namespace exists only while that bundle is on.
 *
 * [UI_ADDONS] is the `dsh-ui-sync` companion plugin, present when `settings/describe` lists its `ui-sync`
 * namespace; with it, desktop web plugin items (a pet, composer buttons) are drawn over the chat. It is
 * [ambient]: the app asks on its own on every direct connection, not because someone opened a page, so a
 * failure to ask is not reported as a connection error. Nothing about the app changes while it is unknown.
 */
enum class HarnessFeature(val ambient: Boolean = false) {
    AUTOMATION, PLUGIN_MANAGEMENT, TIMED_QUESTIONS, SESSION_REFERENCES, PRESET_COMPOSITION, PLUGIN_MARKET,
    HARNESS_SETTINGS, ACCOUNT, UI_ADDONS(ambient = true),
}

/** Connection-scoped evidence: authorization failures do not imply a missing capability. */
internal class FeatureAvailability(private val report: (RpcError) -> Unit) : SynchronizedObject() {
    private val states = HarnessFeature.entries.associateWith { MutableStateFlow<Boolean?>(null) }
    private var epoch = 0L

    fun state(feature: HarnessFeature): StateFlow<Boolean?> = states.getValue(feature).asStateFlow()
    fun generation(): Long = synchronized(this) { epoch }
    fun reset(): Unit = synchronized(this) {
        epoch++
        states.values.forEach { it.value = null }
    }

    /**
     * The harness's modules changed (a bundle was switched on or off), so what it could not do a
     * moment ago it may do now. Every one of [probeable] that reads unavailable goes back to unknown
     * and is returned, for the caller to ask again; without this "unavailable" would last until the
     * app reconnected. A result still in flight from before the change is dropped. A feature that
     * reads available is left alone: the next call that finds it gone says so.
     */
    fun relax(probeable: Set<HarnessFeature>): List<HarnessFeature> = synchronized(this) {
        val lapsed = probeable.filter { states.getValue(it).value == false }
        if (lapsed.isEmpty()) return emptyList()
        epoch++
        lapsed.forEach { states.getValue(it).value = null }
        lapsed
    }

    /**
     * [authoritative] is for a call made only to ask again whether the capability exists now (see
     * [SessionStore.recheckPluginMarket]). An ordinary result never brings back a feature that was retired,
     * because a concurrent read can succeed on a family with a missing method; an answer to the question itself
     * can, for a feature that appears and disappears while connected (the optional market bundle).
     */
    fun observe(feature: HarnessFeature, generation: Long, result: RpcResult<*>, authoritative: Boolean = false): Unit = synchronized(this) {
        if (generation != epoch) return
        val state = states.getValue(feature)
        when (result) {
            is RpcResult.Ok -> if (authoritative || state.value != false) state.value = true
            is RpcResult.Err -> if (result.error.classifyCapability().code == "capability-unavailable") {
                state.value = false
            } else if (!feature.ambient) {
                // Unknown stays visible; authentication and carrier failures are not capability evidence.
                report(result.error)
            }
        }
    }
}
