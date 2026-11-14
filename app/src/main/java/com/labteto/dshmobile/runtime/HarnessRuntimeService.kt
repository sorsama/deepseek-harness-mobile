package com.labteto.dshmobile.runtime

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import com.labteto.dshmobile.notify.DshNotifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
 */
@AndroidEntryPoint
class HarnessRuntimeService : LifecycleService() {

    @Inject lateinit var controller: HarnessRuntimeController
    @Inject lateinit var notifications: DshNotifications

    private var wakeLock: PowerManager.WakeLock? = null

    private var following = false

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
    }

    /**
     * Follow the controller from the first start command on: only after `startForeground`, since a
     * service started with `startForegroundService` that stops before calling it crashes the app.
     */
    private fun follow() {
        if (following) return
        following = true
        lifecycleScope.launch {
            combine(controller.state, controller.holdWakeLock) { state, hold -> state to hold }.collect { (state, hold) ->
                if (!active(state)) {
                    releaseWakeLock()
                    inForeground = false
                    ServiceCompat.stopForeground(this@HarnessRuntimeService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                notify(state)
                if (hold && (state is RuntimeState.Running || state is RuntimeState.Starting)) acquireWakeLock() else releaseWakeLock()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Into the foreground first, whatever the action: Android requires it soon after
        // startForegroundService, and a stop is quick but not instant.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(controller.state.value), type)
        inForeground = true
        when (intent?.action) {
            ACTION_STOP -> controller.stop()
            ACTION_CANCEL -> controller.cancel()
        }
        follow()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        inForeground = false
        super.onDestroy()
    }

    private fun notify(state: RuntimeState) {
        if (!notifications.canPost()) return
        @Suppress("MissingPermission")
        androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(state))
    }

    private fun build(state: RuntimeState): Notification {
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
        when (state) {
            is RuntimeState.Running -> builder
                .setContentTitle(getString(R.string.runtime_notif_running))
                .setContentText(getString(R.string.runtime_notif_running_text, state.port))
                .addAction(0, getString(R.string.connect_loopback_stop), action(ACTION_STOP))
            is RuntimeState.Starting -> builder
                .setContentTitle(getString(R.string.runtime_notif_starting))
                .setProgress(0, 0, true)
                .addAction(0, getString(R.string.connect_loopback_stop), action(ACTION_STOP))
            is RuntimeState.Stopping -> builder
                .setContentTitle(getString(R.string.connect_loopback_stopping))
                .setProgress(0, 0, true)
            else -> installProgress(builder, state)
        }
        return builder.build()
    }

    private fun installProgress(builder: NotificationCompat.Builder, state: RuntimeState) {
        val phase = when (state) {
            is RuntimeState.Downloading -> UpdatePhase.Downloading(state.progress)
            is RuntimeState.Verifying -> UpdatePhase.Verifying(state.layer)
            is RuntimeState.Unpacking -> UpdatePhase.Unpacking(state.progress)
            is RuntimeState.Updating -> state.phase
            else -> null
        }
        builder.setContentTitle(getString(R.string.runtime_notif_installing))
        when (phase) {
            is UpdatePhase.Downloading -> builder
                .setContentText(getString(R.string.runtime_notif_downloading, phase.progress.percent))
                .setProgress(100, phase.progress.percent, false)
            is UpdatePhase.Unpacking -> builder
                .setContentText(getString(R.string.runtime_notif_unpacking))
                .setProgress(100, phase.progress.percent, false)
            else -> builder.setProgress(0, 0, true)
        }
        if (phase !is UpdatePhase.Switching) builder.addAction(0, getString(R.string.common_cancel), action(ACTION_CANCEL))
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

    companion object {
        const val ACTION_STOP = "com.labteto.dshmobile.runtime.STOP"
        const val ACTION_CANCEL = "com.labteto.dshmobile.runtime.CANCEL"
        private const val NOTIFICATION_ID = 11

        @Volatile
        internal var inForeground: Boolean = false

        private fun active(state: RuntimeState): Boolean = state.busy || state is RuntimeState.Running
    }
}
