package com.labteto.dshmobile.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Harness updates without an app update: finding them, telling the person once, installing them by
 * itself when asked to, and what happens after.
 *
 * Plain Kotlin over [Ports] (Android: [RuntimeUpdates]), so every rule below is a unit test:
 *
 *  - **Checks** read the release manifest when the app opens and once a day in the background, but
 *    not again within [CHECK_INTERVAL_MS] of the last read; "Check now" always reads it. Automatic
 *    checks follow the app's own "check for updates" switch ([Ports.automaticChecksAllowed]).
 *  - **One notification per release**: a release is told about once, by a notification or by the
 *    person's own check, and never again ([RuntimeUpdatePreferences.notifiedSequence]).
 *  - **Automatic install** (off by default) only on an unmetered network, only while nothing would
 *    be cut off ([autoInstallAllowed]), after the conditions have held for a little while, and never
 *    for a release the person went back from or one that already failed to start here. The person
 *    is told afterwards what changed.
 *  - **Going back**: the release left is remembered as skipped until a newer one appears.
 *  - **Reconnecting**: when the harness comes back up after an update or a restart while the app
 *    was connected to it, the app reconnects at once instead of waiting out its back-off.
 */
class RuntimeUpdateCoordinator(
    private val scope: CoroutineScope,
    private val prefs: RuntimeUpdatePreferences,
    private val runtime: StateFlow<RuntimeState>,
    private val available: StateFlow<AvailableRuntimeUpdate?>,
    private val outcomes: Flow<UpdateOutcome>,
    private val link: StateFlow<LinkState>,
    private val ports: Ports,
    private val clock: () -> Long = System::currentTimeMillis,
    private val settleMs: Long = AUTO_SETTLE_MS,
) {
    interface Ports {
        /** Read the latest release's manifest (the host also keeps what it found). */
        suspend fun check(): RuntimeInstaller.CheckResult

        /** Start an update to the latest release; false when the engine refused (busy). */
        fun install(): Boolean

        /** The app's "check for updates automatically" switch. */
        suspend fun automaticChecksAllowed(): Boolean

        /** Whether the active network does not count against a data plan. */
        fun unmetered(): Boolean

        fun notifyAvailable(update: AvailableRuntimeUpdate)
        fun notifyUpdated(outcome: UpdateOutcome.Applied)
        fun notifyRolledBack(outcome: UpdateOutcome.RolledBack)

        /** The harness is up again on [port]: reconnect now if the app was reconnecting to it. */
        fun reconnect(port: Int)
    }

    /**
     * The app's connection as far as an automatic update cares.
     *
     * @property onBuiltIn the app's connection (live or being re-established) is to this phone's
     *   built-in harness.
     * @property connected that connection is up right now, so [turnRunning] is known.
     * @property turnRunning a session on it is in the middle of a turn.
     */
    data class LinkState(val onBuiltIn: Boolean = false, val connected: Boolean = false, val turnRunning: Boolean = false)

    /** The sequence of the release an automatic install started, until its outcome is known. */
    @Volatile
    private var automaticSequence: Long? = null

    /** An automatic install of this release failed (a download that broke off): wait for the next check. */
    @Volatile
    private var autoBlockedSequence: Long? = null

    /** Bumped by each check that reached the server, so the automatic install looks again. */
    private val checked = MutableStateFlow(0)

    fun start() {
        scope.launch { outcomes.collect(::onOutcome) }
        scope.launch { followRunning() }
        scope.launch { autoInstallLoop() }
    }

    /** The check when the app comes to the foreground. */
    suspend fun onAppOpen(): RuntimeInstaller.CheckResult? = automaticCheck()

    /** The daily background check. */
    suspend fun onBackground(): RuntimeInstaller.CheckResult? = automaticCheck()

    /** "Check now": always reads the manifest, and never notifies (the person is looking). */
    suspend fun checkNow(): RuntimeInstaller.CheckResult = runCheck(manual = true)

    private suspend fun automaticCheck(): RuntimeInstaller.CheckResult? {
        if (!runtime.value.hasInstall()) return null
        if (!shouldCheck(clock(), prefs.lastCheckAt, CHECK_INTERVAL_MS)) return null
        if (!ports.automaticChecksAllowed()) return null
        return runCheck(manual = false)
    }

    private suspend fun runCheck(manual: Boolean): RuntimeInstaller.CheckResult {
        val result = ports.check()
        // A check that could not reach the server did not read anything: the next opening tries again.
        val reached = !(result is RuntimeInstaller.CheckResult.Refused && result.failure is RuntimeFailure.Network)
        if (reached) {
            prefs.lastCheckAt = clock()
            autoBlockedSequence = null
            checked.value++
        }
        if (result is RuntimeInstaller.CheckResult.Available) consider(result.toUpdate(), manual)
        return result
    }

    /** A check found [update]: forget an older skip, and tell the person once. */
    private fun consider(update: AvailableRuntimeUpdate, manual: Boolean) {
        prefs.skipped.value?.let { if (update.sequence > it.sequence) prefs.setSkipped(null) }
        if (isSkipped(update, prefs.skipped.value)) return
        if (update.sequence <= prefs.notifiedSequence) return
        // Told about now, by this notification or by the screen the person is looking at.
        prefs.notifiedSequence = update.sequence
        if (manual || !prefs.notify.value) return
        // It is about to install by itself: the notification that matters is the one afterwards.
        if (prefs.autoInstall.value && autoInstallAllowed(snapshot(update), ports.unmetered())) return
        ports.notifyAvailable(update)
    }

    private fun onOutcome(outcome: UpdateOutcome) {
        val automatic = automaticSequence != null && automaticSequence == outcome.to.sequence
        when (outcome) {
            is UpdateOutcome.Applied -> {
                if (outcome.wentBack) {
                    prefs.setSkipped(outcome.from)
                    // Read the latest again, so the screens show it as the release the person left.
                    scope.launch { runCatching { ports.check() } }
                } else {
                    prefs.skipped.value?.let { if (outcome.to.sequence >= it.sequence) prefs.setSkipped(null) }
                    if (automatic && prefs.notify.value) ports.notifyUpdated(outcome)
                }
                // The outcome of a start on trial is still to come; keep [automaticSequence] for it.
                if (!automatic) automaticSequence = null
            }
            is UpdateOutcome.RolledBack -> {
                prefs.setFailed(outcome.to)
                if (automatic && prefs.notify.value) ports.notifyRolledBack(outcome)
                automaticSequence = null
            }
            is UpdateOutcome.Failed -> {
                if (automatic) autoBlockedSequence = outcome.to.sequence
                automaticSequence = null
            }
        }
    }

    private suspend fun followRunning() {
        var wasRunning = runtime.value is RuntimeState.Running
        runtime.collect { state ->
            val running = state is RuntimeState.Running
            if (running && !wasRunning) {
                ports.reconnect((state as RuntimeState.Running).port)
                // Ready once: an automatic update is settled, whatever happens later.
                if (!state.installed.harnessOnTrial && state.rolledBack == null) automaticSequence = null
            }
            wasRunning = running
        }
    }

    private fun snapshot(update: AvailableRuntimeUpdate?) = AutoInstallInputs(
        enabled = prefs.autoInstall.value,
        update = update,
        skipped = prefs.skipped.value,
        failed = prefs.failed.value,
        blockedSequence = autoBlockedSequence,
        runtime = runtime.value,
        link = link.value,
    )

    private suspend fun autoInstallLoop() {
        val memory = combine(prefs.skipped, prefs.failed, checked) { skipped, failed, n -> Triple(skipped, failed, n) }
        combine(runtime, available, link, prefs.autoInstall, memory) { _, update, _, _, _ -> update }
            .collectLatest { update ->
                if (!autoInstallAllowed(snapshot(update), ports.unmetered())) return@collectLatest
                // Held for a while first: a harness that was just stopped is often started again.
                delay(settleMs)
                val now = available.value ?: return@collectLatest
                if (!autoInstallAllowed(snapshot(now), ports.unmetered())) return@collectLatest
                automaticSequence = now.sequence
                if (!ports.install()) automaticSequence = null
            }
    }

    companion object {
        /** Automatic checks: not more than once in four hours, however often the app opens. */
        const val CHECK_INTERVAL_MS = 4L * 60 * 60 * 1000

        /** How long the conditions for an automatic install must hold before it starts. */
        const val AUTO_SETTLE_MS = 30_000L
    }
}

/** Whether an automatic check is due: never checked, or the last read is [intervalMs] old. */
internal fun shouldCheck(now: Long, lastCheckAt: Long, intervalMs: Long): Boolean =
    lastCheckAt <= 0 || now - lastCheckAt >= intervalMs || now < lastCheckAt

/** [update] is the release the person went back from, or an older one. */
internal fun isSkipped(update: AvailableRuntimeUpdate, skipped: ReleaseRef?): Boolean =
    skipped != null && update.sequence <= skipped.sequence

internal data class AutoInstallInputs(
    val enabled: Boolean,
    val update: AvailableRuntimeUpdate?,
    val skipped: ReleaseRef?,
    val failed: ReleaseRef?,
    val blockedSequence: Long?,
    val runtime: RuntimeState,
    val link: RuntimeUpdateCoordinator.LinkState,
)

/**
 * Whether an update may install by itself right now.
 *
 * The switch is on, there is a release that was neither left on purpose nor already failed here,
 * the network is unmetered, and nothing would be cut off: the harness is stopped, or it runs and
 * either the app is not connected to it or it is connected and no session is in a turn. While the
 * app is reconnecting to it, whether a turn runs is unknown, so the answer is no.
 */
internal fun autoInstallAllowed(inputs: AutoInstallInputs, unmetered: Boolean): Boolean {
    val update = inputs.update ?: return false
    if (!inputs.enabled || !unmetered) return false
    if (isSkipped(update, inputs.skipped)) return false
    if (inputs.failed != null && update.sequence <= inputs.failed.sequence) return false
    if (inputs.blockedSequence == update.sequence) return false
    return when (val r = inputs.runtime) {
        is RuntimeState.Stopped -> true
        is RuntimeState.Failed -> r.installed != null
        is RuntimeState.Running -> !inputs.link.onBuiltIn || inputs.link.connected && !inputs.link.turnRunning
        else -> false
    }
}
