package com.labteto.dshmobile.runtime

/**
 * Bring the built-in harness back by itself after the person updated the app from inside the app.
 *
 * Installing an update of this app ends its process, and the harness (a child of that process)
 * with it. Before the in-app updater hands the new APK to the system it writes a [Marker] when the
 * harness was running or starting, or when it is set to start with the app; after the update
 * the app's `ACTION_MY_PACKAGE_REPLACED` receiver ([AppUpdatedReceiver]) asks [decide] what to do,
 * and so does the first screen the person opens, for the case where the receiver could not start
 * it (Android refused, the runtime was busy, the receiver never ran).
 *
 * Plain Kotlin so the rules run in JVM tests; [Store] is the persistence seam.
 */
object ResumeAfterUpdate {

    /** Written just before the install session is committed. */
    data class Marker(
        /** The port to start on: the one the harness was running on, or the last one used. */
        val port: Int,
        /** The version code of the build that wrote this; the update has landed once it rose. */
        val fromVersionCode: Long,
        /** When it was written (wall clock), so a marker for an update that never landed expires. */
        val atEpochMs: Long,
    )

    sealed interface Decision {
        /** Start the harness on [port], then forget the marker. */
        data class Start(val port: Int) : Decision

        /** Not yet: the update has not landed, or the runtime is busy. Keep the marker. */
        data object Wait : Decision

        /** Nothing to do any more (already running, nothing installed, too old). Forget the marker. */
        data object Drop : Decision
    }

    /**
     * How long a marker stays good. An update can wait for the person's confirmation for a while;
     * a day later a harness that suddenly starts would be a surprise.
     */
    const val TTL_MS: Long = 24L * 60 * 60 * 1000

    /** The port to remember before an update, or null when nothing should come back afterwards. */
    fun portToResume(state: RuntimeState, autoStart: Boolean, lastPort: Int?): Int? = when (state) {
        is RuntimeState.Running -> state.port
        is RuntimeState.Starting -> state.port
        // The person stopped it, or never started it: leave it stopped unless it is set to start
        // with the app anyway.
        is RuntimeState.Stopped, is RuntimeState.Updating -> if (autoStart) lastPort ?: DEFAULT_PORT else null
        is RuntimeState.Failed -> if (autoStart && state.installed != null) lastPort ?: DEFAULT_PORT else null
        is RuntimeState.Stopping -> if (autoStart) state.port else null
        else -> null
    }

    fun decide(marker: Marker?, currentVersionCode: Long, nowEpochMs: Long, state: RuntimeState): Decision? {
        if (marker == null) return null
        val age = nowEpochMs - marker.atEpochMs
        if (age > TTL_MS || age < -CLOCK_SLACK_MS) return Decision.Drop
        // Still the build that wrote it: the update has not happened (yet, or it failed quietly).
        if (currentVersionCode <= marker.fromVersionCode) return Decision.Wait
        return when (state) {
            is RuntimeState.Running, is RuntimeState.Starting -> Decision.Drop
            is RuntimeState.NotInstalled, is RuntimeState.NotSupported -> Decision.Drop
            is RuntimeState.Stopped -> Decision.Start(marker.port)
            is RuntimeState.Failed -> if (state.installed != null) Decision.Start(marker.port) else Decision.Drop
            else -> if (state.busy) Decision.Wait else Decision.Drop
        }
    }

    /** The Connect screen's default port, used when no start was ever seen. */
    const val DEFAULT_PORT = 3080

    /** A wall clock that moved back a little (network time) does not void a marker. */
    private const val CLOCK_SLACK_MS = 10L * 60 * 1000

    /** Where the marker lives; synchronous, because it is written right before the process ends. */
    interface Store {
        fun read(): Marker?
        fun write(marker: Marker?)
        fun lastPort(): Int?
        fun rememberPort(port: Int)
    }

    /** [Store] in a private SharedPreferences file. */
    class SharedStore(private val prefs: android.content.SharedPreferences) : Store {
        override fun read(): Marker? {
            if (!prefs.contains(KEY_PORT)) return null
            val port = prefs.getInt(KEY_PORT, -1).takeIf { it in 1..65535 } ?: return null
            return Marker(port, prefs.getLong(KEY_FROM, 0L), prefs.getLong(KEY_AT, 0L))
        }

        // commit(), not apply(): the marker is written just before an install that ends this
        // process, and a write still queued in the background would be lost with it.
        @android.annotation.SuppressLint("ApplySharedPref")
        override fun write(marker: Marker?) {
            val edit = prefs.edit()
            if (marker == null) {
                edit.remove(KEY_PORT).remove(KEY_FROM).remove(KEY_AT)
            } else {
                edit.putInt(KEY_PORT, marker.port).putLong(KEY_FROM, marker.fromVersionCode).putLong(KEY_AT, marker.atEpochMs)
            }
            edit.commit()
        }

        override fun lastPort(): Int? = prefs.getInt(KEY_LAST_PORT, -1).takeIf { it in 1..65535 }

        override fun rememberPort(port: Int) {
            if (lastPort() != port) prefs.edit().putInt(KEY_LAST_PORT, port).apply()
        }

        companion object {
            const val FILE = "runtime_resume"
            private const val KEY_PORT = "resume_port"
            private const val KEY_FROM = "resume_from_version_code"
            private const val KEY_AT = "resume_at"
            private const val KEY_LAST_PORT = "last_port"
        }
    }
}

/** The in-app updater's side: remember before the install, forget when it fails. */
@javax.inject.Singleton
class ResumeAfterUpdateHooks @javax.inject.Inject constructor(
    private val controller: HarnessRuntimeController,
    private val hostsStore: com.labteto.dshmobile.connection.HostsStore,
) : com.labteto.dshmobile.update.SelfUpdateHooks {
    override suspend fun beforeInstall() {
        val autoStart = runCatching { hostsStore.settingsOnce().autoStartBuiltInHarness }.getOrDefault(false)
        controller.rememberForAppUpdate(autoStart)
    }

    override fun installFailed() = controller.forgetAppUpdate()
}
