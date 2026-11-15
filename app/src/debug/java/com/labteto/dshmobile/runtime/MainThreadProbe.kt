package com.labteto.dshmobile.runtime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Debug builds only: how long the main thread takes to get to a message posted to it.
 *
 * A background thread posts a no-op to the main looper every [PERIOD_MS] and notes how long the
 * main thread took to run it. That is the delay a service start (`onCreate`, `onStartCommand`)
 * would see at that moment, so it is the number that matters for the foreground-service start
 * timeout. A stall longer than [STALL_MS] logs the main thread's stack while it is still stuck, so
 * the cause is named rather than guessed. `RuntimeDebugActivity` op `mainstats` prints the summary
 * since the last `mainstats reset`.
 */
object MainThreadProbe {
    private const val TAG = "DshMainProbe"
    private const val PERIOD_MS = 50L
    private const val STALL_MS = 400L

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val delays = ArrayList<Long>()
    private var started = false

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        Thread({ loop() }, "main-probe").apply { isDaemon = true }.start()
    }

    private fun loop() {
        while (true) {
            val posted = SystemClock.uptimeMillis()
            val done = Object()
            val holder = LongArray(1)
            main.post {
                holder[0] = SystemClock.uptimeMillis()
                synchronized(done) { done.notifyAll() }
            }
            var sampled = false
            synchronized(done) {
                while (holder[0] == 0L) {
                    val waited = SystemClock.uptimeMillis() - posted
                    if (waited >= STALL_MS && !sampled) {
                        sampled = true
                        Log.w(TAG, "main thread stalled ${waited} ms and counting; stack:\n" + stack())
                    }
                    done.wait(20)
                }
            }
            val delay = holder[0] - posted
            synchronized(lock) { delays.add(delay) }
            if (delay >= STALL_MS) Log.w(TAG, "main thread stalled $delay ms")
            Thread.sleep(PERIOD_MS)
        }
    }

    private fun stack(): String =
        Looper.getMainLooper().thread.stackTrace.take(40).joinToString("\n") { "    at $it" }

    fun reset() = synchronized(lock) { delays.clear() }

    /** One line: samples, p50, p95, p99, max, and the time spent waiting over 100 ms. */
    fun summary(): String = synchronized(lock) {
        if (delays.isEmpty()) return "no samples"
        val sorted = delays.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        "samples=${sorted.size} p50=${pct(0.5)}ms p95=${pct(0.95)}ms p99=${pct(0.99)}ms max=${sorted.last()}ms over100ms=${sorted.count { it > 100 }} over400ms=${sorted.count { it > 400 }}"
    }
}
