package com.labteto.dshmobile.update

/**
 * Download speed over the last few seconds, for "2.1 MB/s, about 9 s left".
 *
 * A sliding window rather than bytes-since-start: after a resume or a slow patch the average since
 * the start lies for a long time, and the time left would count down at the wrong pace.
 */
internal class TransferRate(private val windowMs: Long = 3_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun reset() = samples.clear()

    /** Record that [bytes] were on disk at [nowMs]. */
    fun sample(nowMs: Long, bytes: Long) {
        samples.addLast(nowMs to bytes)
        while (samples.size > 2 && nowMs - samples.first().first > windowMs) samples.removeFirst()
    }

    /** Bytes per second, or null until there is half a second of data to go on. */
    fun bytesPerSecond(): Long? {
        if (samples.size < 2) return null
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        val dt = t1 - t0
        if (dt < 500 || b1 < b0) return null
        return (b1 - b0) * 1000 / dt
    }
}

/** Seconds left at [bytesPerSecond], or null when either side is unknown. */
internal fun secondsLeft(done: Long, total: Long, bytesPerSecond: Long?): Long? {
    if (total <= 0 || bytesPerSecond == null || bytesPerSecond <= 0) return null
    val remaining = (total - done).coerceAtLeast(0)
    return (remaining + bytesPerSecond - 1) / bytesPerSecond
}

/**
 * Bytes the update needs free in the cache before it starts: what is left to download, plus a
 * second copy for the install session the system stages, plus a margin so the phone is not left
 * with nothing. An unknown [total] assumes a 64 MB APK.
 */
internal fun bytesNeededFor(total: Long, alreadyOnDisk: Long): Long {
    val size = if (total > 0) total else 64L * 1024 * 1024
    val remaining = (size - alreadyOnDisk).coerceAtLeast(0)
    return remaining + size + SPACE_MARGIN
}

internal const val SPACE_MARGIN: Long = 32L * 1024 * 1024
