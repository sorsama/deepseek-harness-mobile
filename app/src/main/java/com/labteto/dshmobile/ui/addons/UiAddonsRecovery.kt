package com.labteto.dshmobile.ui.addons

/**
 * When a page that lived through a dropped connection is loaded again, without a device.
 *
 * A page that was up while the harness connection (or the device network under it) dropped can be
 * left in a state TalkBack answers with about nine `windowContentChanged: node is null` events a
 * second until the document is replaced; a reload clears it. So a drop followed by a recovery
 * reloads the page once. The rule:
 *
 * - The first connect for a host is never a recovery: only a drop after being connected counts.
 * - Many drops before the connection holds again are one recovery, not many.
 * - The reload waits until the connection has been up for [settleMs] without a break, so a flapping
 *   link reloads once at the end of it, not on each bounce.
 * - Two reloads are at least [minGapMs] apart, however the link behaves.
 * - A recovery with no page to reload is used up, so a page made later is not reloaded for it.
 *
 * Time is passed in, as milliseconds on any monotonic clock. Main thread only.
 */
internal class UiAddonsRecovery(
    private val settleMs: Long = UI_ADDONS_RECOVERY_SETTLE_MS,
    private val minGapMs: Long = UI_ADDONS_RECOVERY_MIN_GAP_MS,
) {
    private var host: String? = null
    private var up = false
    private var upSince = 0L
    private var dropped = false
    private var lastReloadAt: Long? = null

    /**
     * The connection to [host] is up ([connected] true) or not, at [now]. A different [host] starts
     * over: its first connect is not a recovery. Network and harness state are one signal here, so
     * the caller combines them.
     */
    fun onConnection(host: String?, connected: Boolean, now: Long) {
        if (host != this.host) {
            this.host = host
            up = false
            dropped = false
            lastReloadAt = null
        }
        if (host == null) return
        if (connected && !up) {
            up = true
            upSince = now
        } else if (!connected && up) {
            up = false
            dropped = true
        }
    }

    /** A page was just made while the connection was up: it loads fresh, so nothing earlier is owed to it. */
    fun onPageMade() {
        if (up) dropped = false
    }

    /**
     * Milliseconds from [now] until a reload would be due if nothing changes, or null when none is
     * owed (no drop since the last reload, or the connection is down: it is asked again when it comes back).
     */
    fun waitMs(now: Long): Long? {
        if (!dropped || !up) return null
        val settled = upSince + settleMs - now
        val spaced = lastReloadAt?.let { it + minGapMs - now } ?: 0L
        return maxOf(settled, spaced, 0L)
    }

    /**
     * Whether to reload the page now, once per recovery. [hasPage]: there is a page to reload; with
     * none the recovery is used up and the answer is false.
     */
    fun take(now: Long, hasPage: Boolean): Boolean {
        if (waitMs(now) != 0L) return false
        dropped = false
        if (!hasPage) return false
        lastReloadAt = now
        return true
    }
}

/** How long the connection must hold, unbroken, before a recovery reloads the page. */
internal const val UI_ADDONS_RECOVERY_SETTLE_MS = 1_000L

/** The least time between two recovery reloads. */
internal const val UI_ADDONS_RECOVERY_MIN_GAP_MS = 10_000L

/**
 * Whether the device's network can break the page's connection to this harness: false for the phone
 * itself (the built-in harness, on loopback), where no network change reaches it. [loopback] is the
 * host's own flag.
 */
internal fun uiAddonsNetworkMatters(loopback: Boolean): Boolean = !loopback
