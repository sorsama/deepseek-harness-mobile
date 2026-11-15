package com.labteto.dshmobile.ui.screens.runtime

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.labteto.dshmobile.update.UpdateFeed

// The system screens the built-in harness's pages open, and what they read back from the system.
// Each opener falls back to the app's own settings page when a device does not have the screen.

internal object RuntimeSystem {

    fun notificationsEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun batteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Android 12 added the limit on an app's child processes (the "phantom process" killer). */
    val hasChildProcessLimit: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Android 14 added "Disable child process restrictions" to Developer options. */
    val hasChildProcessSwitch: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * Whether "Disable child process restrictions" is on, when the system lets an app read it:
     * the switch writes `settings_enable_monitor_phantom_procs` to false. Null when unknown.
     */
    fun childRestrictionsOff(context: Context): Boolean? {
        if (!hasChildProcessSwitch) return null
        val value = runCatching { Settings.Global.getString(context.contentResolver, "settings_enable_monitor_phantom_procs") }.getOrNull()
        return when (value?.lowercase()) {
            "false" -> true
            "true" -> false
            else -> null
        }
    }

    fun openNotificationSettings(context: Context) = open(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openBatterySettings(context: Context) = open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    fun openDeveloperOptions(context: Context) = open(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

    fun openStorageSettings(context: Context) = open(context, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))

    /** Where this app is updated from when the in-app updater has nothing to offer: its releases page. */
    fun openAppUpdate(context: Context) = open(context, Intent(Intent.ACTION_VIEW, Uri.parse(UpdateFeed.GitHub.releasesPageUrl)))

    fun share(context: Context, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        open(context, Intent.createChooser(send, title))
    }

    private fun open(context: Context, intent: Intent) {
        val flagged = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(flagged)
        } catch (e: ActivityNotFoundException) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } catch (e: SecurityException) {
            // Some OEM builds guard a settings screen; there is nothing better to open.
        }
    }
}
