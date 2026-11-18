package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.classifyCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HarnessFeature { AUTOMATION, PLUGIN_MANAGEMENT, TIMED_QUESTIONS, SESSION_REFERENCES, HARNESS_SETTINGS, ACCOUNT }

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

    @Synchronized fun observe(feature: HarnessFeature, generation: Long, result: RpcResult<*>) {
        if (generation != epoch) return
        val state = states.getValue(feature)
        when (result) {
            is RpcResult.Ok -> if (state.value != false) state.value = true
            is RpcResult.Err -> if (result.error.classifyCapability().code == "capability-unavailable") {
                state.value = false
            } else {
                // Unknown stays visible; authentication and carrier failures are not capability evidence.
                report(result.error)
            }
        }
    }
}
