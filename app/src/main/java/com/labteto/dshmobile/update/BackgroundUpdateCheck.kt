package com.labteto.dshmobile.update

import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.notify.DshNotifications
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Should the background check run now? Once a day at most, and never when the clock went
 * backwards past the last run (a changed time zone or a reset clock should not hold it off for a day).
 */
internal fun backgroundCheckDue(nowMs: Long, lastRunMs: Long, intervalMs: Long = BackgroundUpdateCheck.INTERVAL_MS): Boolean =
    lastRunMs <= 0 || nowMs < lastRunMs || nowMs - lastRunMs >= intervalMs

/**
 * "A new version is out", as a notification, when the app is not open.
 *
 * It does not have a scheduler of its own: it rides on [com.labteto.dshmobile.connection.KeepAliveWorker],
 * the periodic job the app already runs, and limits itself to one request a day. It only runs with
 * both "Check for updates" and "Notify me about new versions" on, says nothing about a release the
 * person declined, and notifies once per release.
 */
@Singleton
class BackgroundUpdateCheck @Inject constructor(
    private val hostsStore: HostsStore,
    private val checker: UpdateChecker,
    private val notifications: DshNotifications,
    private val prefs: UpdaterPrefs,
) {
    suspend fun runIfDue(nowMs: Long = System.currentTimeMillis()) {
        val settings = runCatching { hostsStore.settingsOnce() }.getOrNull() ?: return
        if (!settings.updateCheckEnabled || !settings.updateNotifyEnabled) return
        if (!backgroundCheckDue(nowMs, prefs.getLong(KEY_LAST_RUN))) return
        prefs.putLong(KEY_LAST_RUN, nowMs)
        val outcome = checker.checkInBackground(BuildConfig.VERSION_NAME)
        val update = (outcome as? UpdateChecker.CheckOutcome.Available)?.update ?: return
        if (prefs.getString(KEY_NOTIFIED) == update.version) return
        notifications.postUpdateAvailable(update.version)
        prefs.putString(KEY_NOTIFIED, update.version)
    }

    companion object {
        const val INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val KEY_LAST_RUN = "background_check_at"
        private const val KEY_NOTIFIED = "notified_version"
    }
}
