package com.labteto.dshmobile.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.notify.DshNotifications
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The Android half of harness updates: [RuntimeUpdateCoordinator] wired to the app.
 *
 * Started once from `DshApplication`. It schedules the daily background check while a runtime is
 * installed (WorkManager, only with a network and not on a low battery, the same mechanism as
 * [com.labteto.dshmobile.connection.KeepAliveWorker]), reads the connection and the session list to
 * know whether an automatic update would cut a turn off, and posts the update notifications on
 * their own quiet channel.
 */
@Singleton
class RuntimeUpdates @Inject constructor(
    @ApplicationContext private val context: Context,
    private val host: BuiltInHarnessHost,
    private val connection: ConnectionManager,
    private val sessions: Provider<SessionStore>,
    private val hostsStore: HostsStore,
    private val notifications: DshNotifications,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller get() = host.controller

    private val coordinator: RuntimeUpdateCoordinator by lazy {
        val link = combine(connection.state, sessions.get().sessions, controller.state) { conn, rows, runtime ->
            val port = (runtime as? RuntimeState.Running)?.port ?: host.port
            val active = conn.host
            val onBuiltIn = active != null && active.isLoopback && !active.isRelay && active.port == port && runtime.hasInstall()
            RuntimeUpdateCoordinator.LinkState(
                onBuiltIn = onBuiltIn,
                connected = onBuiltIn && conn.phase == ConnectionPhase.CONNECTED,
                turnRunning = rows.any { it.running },
            )
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, RuntimeUpdateCoordinator.LinkState())

        RuntimeUpdateCoordinator(
            scope = scope,
            prefs = controller.updatePreferences,
            runtime = controller.state,
            available = controller.availableUpdate,
            outcomes = controller.outcomes,
            link = link,
            ports = object : RuntimeUpdateCoordinator.Ports {
                override suspend fun check(): RuntimeInstaller.CheckResult = host.checkRelease()
                override fun install(): Boolean = host.update()
                override suspend fun automaticChecksAllowed(): Boolean =
                    runCatching { hostsStore.settingsOnce().updateCheckEnabled }.getOrDefault(true)
                override fun unmetered(): Boolean = isUnmetered()
                override fun notifyAvailable(update: AvailableRuntimeUpdate) = post(
                    NOTIFY_AVAILABLE,
                    context.getString(R.string.runtime_notif_update_title),
                    context.getString(R.string.runtime_notif_update_text, update.releaseId, update.harnessVersion),
                )
                override fun notifyUpdated(outcome: UpdateOutcome.Applied) = post(
                    NOTIFY_AVAILABLE,
                    context.getString(R.string.runtime_notif_updated_title),
                    context.getString(R.string.runtime_notif_updated_text, outcome.to.releaseId, outcome.to.harnessVersion),
                )
                override fun notifyRolledBack(outcome: UpdateOutcome.RolledBack) = post(
                    NOTIFY_AVAILABLE,
                    context.getString(R.string.runtime_notif_rolled_back_title),
                    context.getString(R.string.runtime_notif_rolled_back_text, outcome.from.releaseId),
                )
                override fun reconnect(port: Int) = reconnectIfWaiting(port)
            },
        )
    }

    fun start() {
        coordinator.start()
        // The daily check only while there is something to update.
        scope.launch {
            controller.state.map { it.hasInstall() }.distinctUntilChanged().collect { installed ->
                if (installed) RuntimeUpdateWorker.schedule(context) else RuntimeUpdateWorker.cancel(context)
            }
        }
    }

    /** The app came to the foreground. */
    fun onAppOpen() {
        scope.launch { runCatching { coordinator.onAppOpen() } }
    }

    suspend fun onBackground() = coordinator.onBackground()

    /** "Check now" on the control page. */
    suspend fun checkNow(): RuntimeInstaller.CheckResult = coordinator.checkNow()

    private fun post(id: Int, title: String, text: String) = notifications.postRuntimeUpdate(id, title, text)

    /**
     * The harness is back up on [port]. If the app is still trying to get back to it (an update
     * or a restart while it was connected), try now rather than at the end of the back-off.
     */
    private fun reconnectIfWaiting(port: Int) {
        val state = connection.state.value
        val active = state.host ?: return
        if (!active.isLoopback || active.isRelay || active.port != port) return
        if (state.phase == ConnectionPhase.RECONNECTING) connection.reconnectIfNeeded()
    }

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && !cm.isActiveNetworkMetered
    }

    private companion object {
        /** One notification at a time about harness updates: each new one replaces the last. */
        const val NOTIFY_AVAILABLE = 4101
    }
}

/**
 * The daily background check for harness updates. Runs only with a network and not on a low
 * battery; the coordinator still skips it when the app checked within the last few hours.
 */
class RuntimeUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun runtimeUpdates(): RuntimeUpdates
    }

    override suspend fun doWork(): Result {
        val updates = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java).runtimeUpdates()
        runCatching { updates.onBackground() }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "dsh-runtime-update-check"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<RuntimeUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
