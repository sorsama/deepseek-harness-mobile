package com.labteto.dshmobile.data

import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/** Milliseconds since the Unix epoch, from the wall clock: what `System.currentTimeMillis()` answered. */
@OptIn(ExperimentalTime::class)
internal fun wallClockMillis(): Long = Clock.System.now().toEpochMilliseconds()

private val monotonicOrigin = TimeSource.Monotonic.markNow()

/**
 * Milliseconds on a clock that never goes back, for measuring how long something took. Only differences between two
 * readings mean anything, as with `System.nanoTime()`, which it replaces.
 */
internal fun monotonicMillis(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds

/** Standard Base64 with padding and no line breaks: what `android.util.Base64.NO_WRAP` wrote. */
internal fun encodeBase64(bytes: ByteArray): String = Base64.Default.encode(bytes)

/**
 * Standard Base64, read as leniently as `android.util.Base64.DEFAULT` read it: padding may be missing, and line
 * breaks or any other character outside the alphabet are skipped. Throws on input that cannot be decoded.
 */
internal fun decodeBase64Lenient(text: String): ByteArray =
    Base64.Mime.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(text)
