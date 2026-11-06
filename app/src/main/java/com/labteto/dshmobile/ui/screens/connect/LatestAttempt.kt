package com.labteto.dshmobile.ui.screens.connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One piece of asynchronous work at a time, which starting again or cancelling puts an end to.
 *
 * The connect screen's pre-flight (validate, probe, remember the host) used to be launched and
 * forgotten. Cancel only reset the screen, so the work carried on behind it and finished by
 * connecting, or by failing over the form, long after the reader had backed out. Holding the job is
 * what gives Cancel something to cancel; the work still has to check [kotlinx.coroutines.ensureActive]
 * after anything that blocks, because a blocking call does not notice a cancel while it runs.
 */
internal class LatestAttempt {
    private var job: Job? = null

    /** True while the last attempt started is still running. */
    val isActive: Boolean get() = job?.isActive == true

    /** Stop any attempt in flight and start [block] in its place. */
    fun start(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        job = scope.launch(block = block)
    }

    /** Stop the attempt in flight, if there is one. */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
