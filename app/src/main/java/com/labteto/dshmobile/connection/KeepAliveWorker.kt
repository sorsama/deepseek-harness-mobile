package com.labteto.dshmobile.connection

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.labteto.dshmobile.update.BackgroundUpdateCheck
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Periodic fallback for users who did not enable the foreground service:
 * if the app still wants a connection (keepConnectedInBackground) and the
 * loop died in the background, this re-establishes it. Runs at most every
 * 15 minutes (WorkManager minimum interval).
 *
 * It is also the only periodic job the app has, so the optional "a new version is out"
 * notification rides on it rather than scheduling a second one: [BackgroundUpdateCheck] does
 * nothing unless that setting is on, and asks GitHub at most once a day.
 */
class KeepAliveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun connectionManager(): ConnectionManager
        fun hostsStore(): HostsStore
        fun backgroundUpdateCheck(): BackgroundUpdateCheck
    }

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
        val settings = entry.hostsStore().settingsOnce()
        runCatching { entry.backgroundUpdateCheck().runIfDue() }
        if (!settings.keepConnectedInBackground) return Result.success()
        val manager = entry.connectionManager()
        val state = manager.state.value
        if (state.host != null && state.phase != ConnectionPhase.CONNECTED) {
            manager.reconnectIfNeeded()
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "dsh-keep-alive"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
