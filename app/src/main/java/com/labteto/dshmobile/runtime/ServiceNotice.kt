package com.labteto.dshmobile.runtime

/**
 * What the service's ongoing notification says for a [RuntimeState], and nothing more.
 *
 * The state changes many times a second during a download or an unpack; the notification only
 * changes when its words or its whole-percent bar do. The service compares these with `==` and
 * posts only on a difference, so a hundred byte-level updates inside one percent cost nothing.
 */
data class ServiceNotice(
    val kind: Kind,
    /** 0 to 100 for [Kind.DOWNLOADING] and [Kind.UNPACKING]; unused otherwise. */
    val percent: Int = 0,
    /** The harness's port for [Kind.RUNNING]; 0 otherwise. */
    val port: Int = 0,
    /** Whether the notification offers Cancel (an install or update that can still be abandoned). */
    val cancellable: Boolean = false,
) {
    enum class Kind { RUNNING, STARTING, STOPPING, DOWNLOADING, UNPACKING, WORKING }
}

/** The notice for this state; see [ServiceNotice]. */
fun RuntimeState.toServiceNotice(): ServiceNotice {
    val phase = when (this) {
        is RuntimeState.Downloading -> UpdatePhase.Downloading(progress)
        is RuntimeState.Verifying -> UpdatePhase.Verifying(layer)
        is RuntimeState.Unpacking -> UpdatePhase.Unpacking(progress)
        is RuntimeState.Updating -> phase
        else -> null
    }
    return when (this) {
        is RuntimeState.Running -> ServiceNotice(ServiceNotice.Kind.RUNNING, port = port)
        is RuntimeState.Starting -> ServiceNotice(ServiceNotice.Kind.STARTING)
        is RuntimeState.Stopping -> ServiceNotice(ServiceNotice.Kind.STOPPING)
        else -> {
            val cancellable = phase !is UpdatePhase.Switching
            when (phase) {
                is UpdatePhase.Downloading -> ServiceNotice(ServiceNotice.Kind.DOWNLOADING, percent = phase.progress.percent, cancellable = cancellable)
                is UpdatePhase.Unpacking -> ServiceNotice(ServiceNotice.Kind.UNPACKING, percent = phase.progress.percent, cancellable = cancellable)
                else -> ServiceNotice(ServiceNotice.Kind.WORKING, cancellable = cancellable)
            }
        }
    }
}

/** Whether the service has anything to cover in this state: work in flight, or a harness running. */
fun RuntimeState.needsService(): Boolean = busy || this is RuntimeState.Running
