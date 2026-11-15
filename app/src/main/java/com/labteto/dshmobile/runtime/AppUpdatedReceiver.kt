package com.labteto.dshmobile.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app was just replaced by a newer build. If the built-in harness was running when the person
 * updated from inside the app, start it again ([ResumeAfterUpdate]).
 *
 * `ACTION_MY_PACKAGE_REPLACED` is one of the broadcasts Android 12 and later let an app start a
 * foreground service from while it is in the background, so the harness's service can come up
 * here. The receiver holds the broadcast ([goAsync]) until the service is in the foreground, or
 * for a few seconds at most; when Android refuses anyway, the harness waits for the app to be
 * opened, where the same check runs again.
 */
class AppUpdatedReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        // Named apart from TermuxResultReceiver's `controller()`: entry point getters share one component.
        fun runtimeController(): HarnessRuntimeController
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val controller = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java).runtimeController()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (controller.resumeAfterAppUpdate("app updated")) {
                    // Keep the broadcast (and with it the exemption) until the service is up.
                    withTimeoutOrNull(HOLD_MS) {
                        while (!controller.serviceStarter.isForeground) delay(POLL_MS)
                    }
                    Log.i(HarnessRuntimeService.TAG, "after app update: service in the foreground=${controller.serviceStarter.isForeground}")
                }
            } catch (e: RuntimeException) {
                Log.w(HarnessRuntimeService.TAG, "after app update: could not start, ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val HOLD_MS = 8_000L
        const val POLL_MS = 100L
    }
}
