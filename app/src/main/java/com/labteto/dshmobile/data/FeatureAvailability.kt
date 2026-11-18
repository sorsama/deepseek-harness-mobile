package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.classifyCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [PLUGIN_MARKET] is the optional "custom plugin markets" bundle: its `pluginMarket` namespace exists only while that bundle is on. */
enum class HarnessFeature {
    AUTOMATION, PLUGIN_MANAGEMENT, TIMED_QUESTIONS, SESSION_REFERENCES, PRESET_COMPOSITION, PLUGIN_MARKET,
    HARNESS_SETTINGS, ACCOUNT,
}

/** Connection-scoped evidence: authorization failures do not imply a missing capability. */
internal class FeatureAvailability(private val report: (RpcError) -> Unit) {
    private val states = HarnessFeature.entries.associateWith { MutableStateFlow<Boolean?>(null) }
    private var epoch = 0L

    fun state(feature: HarnessFeature): StateFlow<Boolean?> = states.getValue(feature).asStateFlow()
    @Synchronized fun generation(): Long = epoch
    @Synchronized fun reset() {
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
    @Synchronized fun relax(probeable: Set<HarnessFeature>): List<HarnessFeature> {
        val lapsed = probeable.filter { states.getValue(it).value == false }
        if (lapsed.isEmpty()) return emptyList()
        epoch++
        lapsed.forEach { states.getValue(it).value = null }
        return lapsed
    }

    /**
     * [authoritative] is for a call made only to ask again whether the capability exists now (see
     * [SessionStore.recheckPluginMarket]). An ordinary result never brings back a feature that was retired,
     * because a concurrent read can succeed on a family with a missing method; an answer to the question itself
     * can, for a feature that appears and disappears while connected (the optional market bundle).
     */
    @Synchronized fun observe(feature: HarnessFeature, generation: Long, result: RpcResult<*>, authoritative: Boolean = false) {
        if (generation != epoch) return
        val state = states.getValue(feature)
        when (result) {
            is RpcResult.Ok -> if (authoritative || state.value != false) state.value = true
            is RpcResult.Err -> if (result.error.classifyCapability().code == "capability-unavailable") {
                state.value = false
            } else {
                // Unknown stays visible; authentication and carrier failures are not capability evidence.
                report(result.error)
            }
        }
    }
}
