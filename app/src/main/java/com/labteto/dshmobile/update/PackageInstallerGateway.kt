package com.labteto.dshmobile.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a verified APK to Android's [PackageInstaller], the way an app store does: no browser, no
 * file manager, no `file://` URI.
 *
 * The session asks for [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED] on Android 12 and
 * newer. The system honours that only when this app is already the installer of record, so the
 * first in-app update still shows the system prompt (the app was installed from a browser or a
 * file manager) and the ones after it can go through without one. Where the system asks anyway,
 * the prompt arrives as [PackageInstaller.STATUS_PENDING_USER_ACTION] and the UI shows it.
 */
@Singleton
class PackageInstallerGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Whether "install unknown apps" is allowed for this app. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where the person allows this app to install updates. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Copy [apk] into a new session and commit it. The outcome arrives at [UpdateInstallReceiver].
     *
     * @return the session id.
     */
    fun commit(apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_STATUS)
                    .setPackage(context.packageName)
                // Mutable: the system fills in the status extras. The intent is explicit (this
                // app's own receiver), which is what Android 14 requires of a mutable PendingIntent.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
        return sessionId
    }

    /**
     * Drop sessions this app opened and never finished: a process that died between writing and
     * committing leaves one behind, holding a copy of the APK in system storage.
     */
    fun abandonStaleSessions(except: Int? = null) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.filter { it.sessionId != except && !it.isActive }.forEach { info ->
            runCatching { installer.abandonSession(info.sessionId) }
        }
    }
}

/**
 * Where [PackageInstaller] reports what happened to a session this app committed. Not exported:
 * the system delivers through the [PendingIntent] this app minted.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun updater(): InAppUpdater
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
            .updater()
            .onInstallStatus(sessionId, status, message, confirm)
    }

    companion object {
        const val ACTION_STATUS = "com.labteto.dshmobile.update.INSTALL_STATUS"
    }
}
