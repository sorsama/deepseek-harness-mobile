package com.labteto.dshmobile.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification channels + post helpers. Completion events come from the
 * core CompletionClassifier (turn end, goal complete/blocked, plan review,
 * approval/question requested).
 */
@Singleton
class DshNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETIONS, context.getString(R.string.notif_channel_completions), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTION, context.getString(R.string.notif_channel_action), NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_LOW),
        )
        // Two quiet channels, kept apart so a person can mute one without the other: a new app
        // version ("App updates") and a new release of the built-in harness ("Harness updates").
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.notif_channel_updates), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNTIME_UPDATES, context.getString(R.string.runtime_notif_channel_updates), NotificationManager.IMPORTANCE_LOW),
        )
    }

    /**
     * A notification about a harness update (one available, one installed, one that did not
     * start). Tapping it opens the harness's control page. One [id] per kind, so a newer one
     * replaces the last instead of piling up.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun postRuntimeUpdate(id: Int, title: String, text: String) {
        ensureChannels()
        if (!canPost()) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN, OPEN_RUNTIME_CONTROLS)
        }
        val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_RUNTIME_UPDATES)
            .setSmallIcon(R.drawable.ic_notification_whale)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Post a completion/action notification that deep-links to a session.
     * [canPost] gates the POST_NOTIFICATIONS runtime permission (API 33+);
     * the lint suppression covers that checked gate.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun postSession(
        channel: String,
        id: Int,
        title: String,
        text: String,
        sessionId: String?,
        actionLabel: String? = null,
    ) {
        if (!canPost()) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) {
                data = android.net.Uri.parse("dshmobile://host/current/session/$sessionId")
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_whale)
            .setContentTitle(title)
            .setContentText(text)
            // A command waiting for approval runs to several lines; expanded, it shows them.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(if (channel == CHANNEL_ACTION) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        if (actionLabel != null) {
            builder.addAction(0, actionLabel, pending)
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /**
     * "Version [version] is out": opens the app, where the update dialog offers the download.
     * Quiet (a low-importance channel): it is news, not something waiting on the person.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun postUpdateAvailable(version: String) {
        if (!canPost()) return
        ensureChannels()
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification_whale)
            .setContentTitle(context.getString(R.string.notif_update_title, version))
            .setContentText(context.getString(R.string.notif_update_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_UPDATES = "updates"
        private const val UPDATE_NOTIFICATION_ID = 0x0D5_0001
        const val CHANNEL_COMPLETIONS = "completions"
        const val CHANNEL_ACTION = "needs_action"
        const val CHANNEL_CONNECTION = "connection"
        const val CHANNEL_RUNTIME_UPDATES = "harness_updates"
        const val EXTRA_SESSION_ID = "dsh_session_id"

        /** Where a notification wants the app to open; [OPEN_RUNTIME_CONTROLS] is the only value so far. */
        const val EXTRA_OPEN = "dsh_open"
        const val OPEN_RUNTIME_CONTROLS = "runtime_controls"
    }
}
