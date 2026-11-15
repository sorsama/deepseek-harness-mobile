package com.labteto.dshmobile.connection

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether an activity of this app is started (on screen), for the rule that decides how a
 * foreground service may be asked for (see [com.labteto.dshmobile.runtime.RuntimeServiceStarter]).
 *
 * Built from the application context when the app process starts, before any activity exists, so
 * the count starts right. [onBecameVisible] runs on the main thread when the first activity starts.
 */
class StartedActivities(context: Context, private val onBecameVisible: () -> Unit) {
    private val started = AtomicInteger()

    val visible: Boolean get() = started.get() > 0

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (started.incrementAndGet() == 1) onBecameVisible()
                }

                override fun onActivityStopped(activity: Activity) {
                    // Never below zero, should a start have been missed.
                    started.updateAndGet { (it - 1).coerceAtLeast(0) }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }
}

/**
 * Runs a service start and says whether Android accepted it. A refusal is an exception on Android 8
 * and later (`ForegroundServiceStartNotAllowedException` and `BackgroundServiceStartNotAllowedException`
 * are both [IllegalStateException]s; a missing foreground-service permission is a [SecurityException]);
 * it becomes `false` here so the caller can ask again later instead of crashing.
 */
internal inline fun serviceStartAccepted(onRefused: (RuntimeException) -> Unit = {}, start: () -> Unit): Boolean = try {
    start()
    true
} catch (e: IllegalStateException) {
    onRefused(e)
    false
} catch (e: SecurityException) {
    onRefused(e)
    false
}
