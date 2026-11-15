package com.labteto.dshmobile.runtime

/**
 * Lets a stream of progress events through at a pace a screen can follow.
 *
 * A download reports every 256 KB and an unpack every 256 entries; on a fast link or a fast disk
 * that is tens of events a second, and every one of them used to become a new state that the set-up
 * page, the Connect card, the service's notification and the update coordinator all had to handle
 * on the main thread. The person sees none of that: a bar that moves a few times a second reads the
 * same. So the engine asks this before it publishes a progress state.
 *
 * An event is let through when it is the first of its [key] (a new layer or a new step), when it is
 * the last of its step, or when [minIntervalMs] has passed since the last one let through. The rest
 * are dropped; nothing is queued, because the next event carries the whole picture (bytes or
 * entries so far), so a dropped one loses nothing the next does not say.
 *
 * Not for the changes that tell the person what step they are on: those are published directly.
 */
class ProgressPacer(
    private val minIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var lastKey: Any? = null
    private var lastAt = 0L

    /** Whether an event of [key] should be published now; [last] is the final event of its step. */
    @Synchronized
    fun admit(key: Any, last: Boolean = false): Boolean {
        val now = nowMs()
        val publish = last || key != lastKey || now - lastAt >= minIntervalMs
        if (publish) {
            lastKey = key
            lastAt = now
        }
        return publish
    }

    /** Forget the last event; the next one is let through. */
    @Synchronized
    fun reset() {
        lastKey = null
        lastAt = 0L
    }

    companion object {
        /** Four updates a second: smooth enough for a bar, cheap enough for a slow phone. */
        const val DEFAULT_INTERVAL_MS = 250L
    }
}
