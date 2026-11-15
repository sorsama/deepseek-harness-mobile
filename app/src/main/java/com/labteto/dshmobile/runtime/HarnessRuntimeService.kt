package com.labteto.dshmobile.runtime

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import com.labteto.dshmobile.notify.DshNotifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Keeps the app process alive while the built-in harness installs or runs, and shows it.
 *
 * Type `specialUse`: the harness is a local server the person started and expects to keep
 * running for hours. `dataSync` would fit the download but is capped at 6 hours a day on Android
 * 15 and would stop a long session; no other type describes a local developer server. The
 * process itself is owned by [HarnessRuntimeController]; this service only pins the process with
 * an ongoing notification (Stop or Cancel) and holds the wake lock.
 *
 * It follows the controller's state and stops itself when nothing is installing or running.
 *
 * Three rules keep it from taking the app down (see [RuntimeServiceStarter] for the caller's side):
 *  - **Foreground first.** `startForeground` is the first thing [onCreate] does after injection and
 *    again the first thing [onStartCommand] does, before the state is read or an action is acted
 *    on. Every request made with `startForegroundService` is answered by it.
 *  - **The main thread is not spent on progress.** The notification is posted when its words or
 *    its whole-percent bar change, and at most once a second ([NOTIFY_INTERVAL_MS]), not once per
 *    state. The state itself is already paced at the source ([ProgressPacer]).
 *  - **Stopping waits.** An install ends in `Stopped` for a moment before the set-up starts the
 *    harness. Stopping the service in that gap and asking for it again, possibly with the app in
 *    the background, is what [STOP_GRACE_MS] and `stopSelfResult` avoid.
 */
@AndroidEntryPoint
class HarnessRuntimeService : LifecycleService() {

    @Inject lateinit var controller: HarnessRuntimeController
    @Inject lateinit var notifications: DshNotifications

    private var wakeLock: PowerManager.WakeLock? = null

    private var following = false
    private var lastStartId = 0
    private var stopJob: Job? = null

    override fun onCreate() {
        val began = SystemClock.uptimeMillis()
        super.onCreate()
        notifications.ensureChannels()
        enterForeground()
        Log.i(TAG, "service created and in the foreground in ${SystemClock.uptimeMillis() - began} ms, ${sinceRequest()} ms after the request")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        // Whatever the action: a request made with startForegroundService is answered by this call,
        // and a stop is quick but not instant.
        val inForeground = enterForeground()
        cancelStop()
        when (intent?.action) {
            ACTION_STOP -> controller.stop()
            ACTION_CANCEL -> controller.cancel()
        }
        if (inForeground) follow()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.serviceStarter.onServiceStopped()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Into the foreground, with a notice that needs nothing but the state's current value. Android
     * 12 and later refuse it when the service was started from the background without an
     * exemption; the service then stops, and the starter makes the request again when the app is
     * next visible (the harness keeps running meanwhile, without the service's protection).
     */
    private fun enterForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(controller.state.value.toServiceNotice()), type)
            controller.serviceStarter.onServiceForeground()
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is one of these.
            Log.w(TAG, "foreground refused: ${e.javaClass.simpleName}")
            controller.serviceStarter.onServiceRefused()
            stopSelf()
            false
        }
    }

    /** Follow the controller from the first start command on. */
    private fun follow() {
        if (following) return
        following = true
        // The notification: the latest content, posted when it changed, at most once a second.
        lifecycleScope.launch {
            controller.state.map { it.toServiceNotice() }.distinctUntilChanged().conflate().collect { notice ->
                notify(notice)
                delay(NOTIFY_INTERVAL_MS)
            }
        }
        // The wake lock and the end of the service.
        lifecycleScope.launch {
            combine(controller.state, controller.holdWakeLock) { state, hold ->
                Duty(active = state.needsService(), holdLock = hold && state !is RuntimeState.Stopping)
            }.distinctUntilChanged().collect { duty ->
                if (!duty.active) {
                    releaseWakeLock()
                    scheduleStop()
                    return@collect
                }
                cancelStop()
                // Held for everything this service covers, not only Running: an update downloads
                // while the old harness keeps serving, and a long download or unpack with the
                // screen off should not stall either.
                if (duty.holdLock) acquireWakeLock() else releaseWakeLock()
            }
        }
    }

    private data class Duty(val active: Boolean, val holdLock: Boolean)

    private fun scheduleStop() {
        if (stopJob?.isActive == true) return
        stopJob = lifecycleScope.launch {
            delay(STOP_GRACE_MS)
            stopIfIdle()
        }
    }

    private fun cancelStop() {
        stopJob?.cancel()
        stopJob = null
    }

    /**
     * The service says it is going first, then looks at the state once more: work that arrived in
     * between asked the starter, saw the service up, and made no request of its own, so it is
     * this check that keeps the service for it. `stopSelfResult` does the same for a start command
     * that is already on its way.
     */
    private fun stopIfIdle() {
        if (!controller.serviceStarter.tryRelease { controller.state.value.needsService() }) return
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (!stopSelfResult(lastStartId)) controller.serviceStarter.onServiceForeground()
    }

    private fun notify(notice: ServiceNotice) {
        if (!notifications.canPost()) return
        notifyCount.incrementAndGet()
        @Suppress("MissingPermission")
        androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(notice))
    }

    private fun build(notice: ServiceNotice): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, DshNotifications.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification_whale)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (notice.kind) {
            ServiceNotice.Kind.RUNNING -> builder
                .setContentTitle(getString(R.string.runtime_notif_running))
                .setContentText(getString(R.string.runtime_notif_running_text, notice.port))
                .addAction(0, getString(R.string.connect_loopback_stop), action(ACTION_STOP))
            ServiceNotice.Kind.STARTING -> builder
                .setContentTitle(getString(R.string.runtime_notif_starting))
                .setProgress(0, 0, true)
                .addAction(0, getString(R.string.connect_loopback_stop), action(ACTION_STOP))
            ServiceNotice.Kind.STOPPING -> builder
                .setContentTitle(getString(R.string.connect_loopback_stopping))
                .setProgress(0, 0, true)
            ServiceNotice.Kind.DOWNLOADING -> installing(builder, notice)
                .setContentText(getString(R.string.runtime_notif_downloading, notice.percent))
                .setProgress(100, notice.percent, false)
            ServiceNotice.Kind.UNPACKING -> installing(builder, notice)
                .setContentText(getString(R.string.runtime_notif_unpacking))
                .setProgress(100, notice.percent, false)
            ServiceNotice.Kind.WORKING -> installing(builder, notice)
                .setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun installing(builder: NotificationCompat.Builder, notice: ServiceNotice): NotificationCompat.Builder {
        builder.setContentTitle(getString(R.string.runtime_notif_installing))
        if (notice.cancellable) builder.addAction(0, getString(R.string.common_cancel), action(ACTION_CANCEL))
        return builder
    }

    private fun action(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, HarnessRuntimeService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        // No timeout on purpose: it is held exactly while the harness runs and released on stop.
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dshmobile:harness-runtime").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun sinceRequest(): Long = (SystemClock.uptimeMillis() - lastRequestAt).coerceAtLeast(0)

    companion object {
        const val ACTION_STOP = "com.labteto.dshmobile.runtime.STOP"
        const val ACTION_CANCEL = "com.labteto.dshmobile.runtime.CANCEL"
        private const val NOTIFICATION_ID = 11
        internal const val TAG = "DshRuntime"

        /** At most this often does the ongoing notification change. */
        const val NOTIFY_INTERVAL_MS = 1_000L

        /**
         * How long the service waits, with nothing to cover, before it stops itself. Covers the
         * moment between an install ending and the set-up starting the harness.
         */
        const val STOP_GRACE_MS = 4_000L

        /** When the app last asked for the service (uptime ms), for the log. */
        @Volatile
        internal var lastRequestAt: Long = 0L

        /** Notification updates made since the app started; the debug driver reads it. */
        internal val notifyCount = AtomicInteger()
    }
}
