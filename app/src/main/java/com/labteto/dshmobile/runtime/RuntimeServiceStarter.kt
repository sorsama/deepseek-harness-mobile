package com.labteto.dshmobile.runtime

/**
 * When and how the app asks for [HarnessRuntimeService]. Plain Kotlin; [Platform] is the Android
 * part, so the rules run in JVM tests.
 *
 * **Which call.** `startForegroundService` starts a clock: if the service has not called
 * `startForeground` within a few seconds of the call, Android kills the app with
 * `ForegroundServiceDidNotStartInTimeException`. The service can only call it from the main
 * thread, so a main thread that is busy (a debug build drawing a progress bar on a large window, a
 * slow phone, a screen full of animation) turns a harmless delay into a crash. While an activity is
 * visible the app may use plain `startService` instead, which starts no clock; the service then
 * moves itself to the foreground as soon as the main thread gets to it. When nothing is visible,
 * `startService` is refused on Android 8 and later, and `startForegroundService` is what is left
 * (it works only where Android makes an exception, and is refused otherwise).
 *
 * **When.** One request at a time: while an earlier one has not been answered by the service
 * ([onServiceForeground]) another would only open a second clock, so it is not made until
 * [PENDING_MS] has passed. A request Android refuses is remembered, and made again when the app
 * is next visible ([onAppVisible]) if the service is still wanted; the harness itself keeps
 * running meanwhile, only without the service's protection.
 */
class RuntimeServiceStarter(
    private val platform: Platform,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    interface Platform {
        /** An activity of the app is started, so Android lets the app start a service. */
        val appVisible: Boolean

        /** Whether something is installing or running that the service should cover. */
        fun wanted(): Boolean

        /** `startService`. False when Android refused because the app is in the background. */
        fun startService(): Boolean

        /** `startForegroundService`. False when Android refused. */
        fun startForegroundService(): Boolean
    }

    enum class Result {
        /** The service is already in the foreground; nothing to do. */
        ALREADY_FOREGROUND,

        /** An earlier request is still waiting for the service; no second one. */
        PENDING,

        /** Asked with `startService`: no clock runs. */
        STARTED,

        /** Asked with `startForegroundService`: the service has to call `startForeground` in time. */
        STARTED_FOREGROUND,

        /** Android refused; remembered for [onAppVisible]. */
        DEFERRED,
    }

    private val lock = Any()
    private var foreground = false
    private var pendingSince: Long? = null
    private var deferred = false

    /** Make sure the service is up (or being brought up). Safe to call from any thread, often. */
    fun ensureStarted(): Result {
        synchronized(lock) {
            if (foreground) return Result.ALREADY_FOREGROUND
            val since = pendingSince
            if (since != null && nowMs() - since < PENDING_MS) return Result.PENDING
        }
        // The platform calls are made outside the lock: they are binder calls.
        val result = when {
            platform.appVisible && platform.startService() -> Result.STARTED
            platform.startForegroundService() -> Result.STARTED_FOREGROUND
            else -> Result.DEFERRED
        }
        synchronized(lock) {
            deferred = result == Result.DEFERRED
            pendingSince = if (deferred) null else nowMs()
        }
        return result
    }

    /** The service has called `startForeground`. */
    fun onServiceForeground() = synchronized(lock) {
        foreground = true
        pendingSince = null
        deferred = false
    }

    /** The service tried to enter the foreground and Android refused; ask again when the app is visible. */
    fun onServiceRefused() = synchronized(lock) {
        foreground = false
        pendingSince = null
        deferred = true
    }

    /**
     * The service has nothing to cover and is about to stop. It says so first, then asks once more
     * ([stillWanted]) whether work arrived meanwhile. The order is the point: work that arrives
     * after the question asks [ensureStarted], finds the service no longer in the foreground, and
     * requests a new one; work that arrived before it is seen by the question. Either way nothing
     * is left running without the service. Returns false when the service must stay.
     */
    fun tryRelease(stillWanted: () -> Boolean): Boolean {
        onServiceStopped()
        if (!stillWanted()) return true
        onServiceForeground()
        return false
    }

    /** The service left the foreground, or is being destroyed. */
    fun onServiceStopped() = synchronized(lock) {
        foreground = false
        pendingSince = null
    }

    /** An activity has started: a request Android refused in the background can be made now. */
    fun onAppVisible(): Result? {
        val retry = synchronized(lock) { deferred }
        return if (retry && platform.wanted()) ensureStarted() else null
    }

    /** Whether the service reported that it is in the foreground. */
    val isForeground: Boolean get() = synchronized(lock) { foreground }

    companion object {
        /**
         * How long an unanswered request blocks the next one. Longer than the time Android allows
         * a service to start, so a request that was lost does not block the app for good.
         */
        const val PENDING_MS = 30_000L
    }
}
