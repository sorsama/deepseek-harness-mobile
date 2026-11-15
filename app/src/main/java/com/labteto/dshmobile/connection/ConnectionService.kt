package com.labteto.dshmobile.connection

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import com.labteto.dshmobile.notify.DshNotifications
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that keeps the WebSocket connection alive while the app
 * is backgrounded (user opt-in via settings). The ConnectionManager owns the
 * actual connection; this service only pins the process with an ongoing
 * notification while it is running.
 *
 * Started by [ConnectionManager.serviceStarter], with the same rules as the built-in harness's
 * service: it enters the foreground first thing in [onCreate] and again first in
 * [onStartCommand], so any request made with `startForegroundService` is answered at once, and a
 * refusal (Android 12+ background limits) stops the service quietly instead of killing the app,
 * and it is asked for again the next time the app is on screen.
 *
 * Type `specialUse`, like the built-in harness's service: it holds a live connection to the
 * person's own harness open for as long as they asked. It used to be `dataSync`, which Android 15
 * caps at six hours a day and then ends with `onTimeout`; `specialUse` has no such limit, so
 * there is no timeout to handle.
 */
@AndroidEntryPoint
class ConnectionService : Service() {

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var notifications: DshNotifications

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
        enterForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // First, whatever the action: a request made with startForegroundService is answered here.
        val inForeground = enterForeground()
        if (!inForeground || intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Restarted by the system after the process died (a sticky restart has no intent): nothing
        // is connected in this new process, so a "Connected" notification would not be true.
        if (intent == null && !connectionManager.serviceStillWanted()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // The service dying does not tear the connection down; the manager owns it.
        connectionManager.serviceStarter.onServiceStopped()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        // The constant exists from API 34, where the type became mandatory. Below it 0 means "the
        // types in the manifest", as for the built-in harness's service.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            connectionManager.serviceStarter.onServiceForeground()
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+).
            Log.w(TAG, "foreground refused: ${e.javaClass.simpleName}")
            connectionManager.serviceStarter.onServiceRefused()
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "foreground refused: ${e.javaClass.simpleName}")
            connectionManager.serviceStarter.onServiceRefused()
            false
        }
    }

    private fun buildNotification(): Notification {
        val host = connectionManager.state.value.host
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = host?.let { getString(R.string.notif_connected_text, it.authority) }
            ?: getString(R.string.common_connected)
        return NotificationCompat.Builder(this, DshNotifications.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification_whale)
            .setContentTitle(getString(R.string.notif_connected_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.labteto.dshmobile.connection.STOP"
        private const val NOTIFICATION_ID = 10
        private const val TAG = "ConnectionService"
    }
}
