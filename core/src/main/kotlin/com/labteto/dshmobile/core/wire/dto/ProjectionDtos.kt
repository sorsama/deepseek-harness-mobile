package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed views over the session projections the harness publishes on `session/projection` frames
 * and in the `session.history` tail block. Every field carries a default: a projection may arrive
 * empty (`contextPressure` is `{}` before the first request) and the wire layer never crashes on
 * partial data.
 */

/**
 * The `sessionStats` projection.
 *
 * Two fields are aggregates, not averages: [ttftMs] is the *sum* of time-to-first-token across
 * [ttftSteps] steps, and throughput has to be derived from [decodeTokens] and [decodeMs]. There is
 * no per-second field on the wire.
 */
@Serializable
data class SessionStatsView(
    @SerialName("turns") val turns: Int = 0,
    @SerialName("steps") val steps: Int = 0,
    @SerialName("llmMs") val llmMs: Long = 0,
    @SerialName("toolMs") val toolMs: Long = 0,
    @SerialName("ttftMs") val ttftMs: Long = 0,
    @SerialName("ttftSteps") val ttftSteps: Int = 0,
    @SerialName("decodeMs") val decodeMs: Long = 0,
    @SerialName("decodeTokens") val decodeTokens: Long = 0,
) {
    /** Mean time to first token, or null before any step has reported one. */
    val meanTtftMs: Long? get() = if (ttftSteps > 0) ttftMs / ttftSteps else null

    /** Decode throughput, or null before any tokens have been decoded. */
    val tokensPerSecond: Double? get() =
        if (decodeMs > 0 && decodeTokens > 0) decodeTokens * 1000.0 / decodeMs else null
}

/** The `tokenUsage` projection: cumulative token accounting for the session. */
@Serializable
data class TokenUsageView(
    @SerialName("uncachedInputTokens") val uncachedInputTokens: Long = 0,
    @SerialName("outputTokens") val outputTokens: Long = 0,
    @SerialName("cacheReadTokens") val cacheReadTokens: Long = 0,
    @SerialName("cacheWriteTokens") val cacheWriteTokens: Long = 0,
) {
    /** Every token that entered the model, cached or not. */
    val inputTokens: Long get() = uncachedInputTokens + cacheReadTokens + cacheWriteTokens

    /** Share of input served from cache, or null when nothing has been sent yet. */
    val cacheHitRatio: Double? get() =
        inputTokens.takeIf { it > 0 }?.let { cacheReadTokens.toDouble() / it }
}

/** The `contextPressure` projection: how full the model's context window is. */
@Serializable
data class ContextPressureView(
    @SerialName("pressureTokens") val pressureTokens: Long? = null,
    @SerialName("projectedTokens") val projectedTokens: Long? = null,
    @SerialName("contextWindow") val contextWindow: Long? = null,
) {
    /** Projected occupancy in `0f..1f`, or null until both numbers are known. */
    val usedRatio: Float? get() {
        val window = contextWindow?.takeIf { it > 0 } ?: return null
        val used = projectedTokens ?: pressureTokens ?: return null
        return (used.toFloat() / window.toFloat()).coerceIn(0f, 1f)
    }
}

/** The `contextBreakdown` projection: what is occupying the context window. */
@Serializable
data class ContextBreakdownView(
    @SerialName("systemTokens") val systemTokens: Long = 0,
    @SerialName("toolsTokens") val toolsTokens: Long = 0,
    @SerialName("messageTokens") val messageTokens: Long = 0,
) {
    val total: Long get() = systemTokens + toolsTokens + messageTokens
}

/**
 * Why a host would refuse one image.
 *
 * The cases are the harness's own admission vocabulary (`packages/attachment/attachment/src/
 * error.ts`), so one string table serves both the pre-check performed here and a rejection that
 * still arrives from the host as an `attachment-error` — see [imageRejectionOf].
 */
enum class ImageRejection {
    /** Not one of the host's accepted media types, or bytes that do not decode as the declared one. */
    UNSUPPORTED_TYPE,

    /** Over `maxImageBytes` on its own. */
    TOO_LARGE,

    /** Width times height is over `maxImagePixels`. */
    TOO_MANY_PIXELS,

    /** One side is over `maxImageDimension`. */
    DIMENSION_TOO_LARGE,

    /** Over `maxImagesPerMessage` images in the one message. */
    TOO_MANY,

    /** The message's images total more than `maxMessageImageBytes`. */
    BATCH_TOO_LARGE,

    /** The session's model takes no images at all. */
    MODEL_UNSUPPORTED,

    /** A subagent transcript, which the harness does not accept images into. */
    SUBAGENT_UNSUPPORTED,

    /** A refusal this client has no copy for; the raw reason is shown instead. */
    UNKNOWN,
}

/**
 * Map an `attachment-error` `details.reason` onto the same vocabulary the pre-check uses.
 * An unrecognised code is [ImageRejection.UNKNOWN] rather than a failure: the reason string is
 * host-owned and may grow, and the caller falls back to showing it verbatim.
 */
fun imageRejectionOf(reason: String): ImageRejection = when (reason) {
    "IMAGE_TOO_LARGE" -> ImageRejection.TOO_LARGE
    "IMAGE_TOO_MANY_PIXELS" -> ImageRejection.TOO_MANY_PIXELS
    "IMAGE_DIMENSION_TOO_LARGE" -> ImageRejection.DIMENSION_TOO_LARGE
    "TOO_MANY_IMAGES" -> ImageRejection.TOO_MANY
    "IMAGES_TOO_LARGE" -> ImageRejection.BATCH_TOO_LARGE
    "UNSUPPORTED_IMAGE_TYPE", "INVALID_IMAGE", "IMAGE_TYPE_MISMATCH", "INVALID_IMAGE_BASE64" ->
        ImageRejection.UNSUPPORTED_TYPE
    "MODEL_DOES_NOT_SUPPORT_IMAGES" -> ImageRejection.MODEL_UNSUPPORTED
    "SUBAGENT_IMAGE_UNSUPPORTED" -> ImageRejection.SUBAGENT_UNSUPPORTED
    else -> ImageRejection.UNKNOWN
}

/**
 * The `imageLimits` projection: the host's own attachment bounds. Defaults mirror the shipped
 * harness so a client that never receives the projection still refuses obviously-oversized images.
 *
 * `maxImageDimension` arrived in harness 0.1.0-rc.8, which also lowered the shipped
 * `maxImageBytes` from 5MB to 3.5MB. Both defaults track the harness rather than this client:
 * an rc.7 host sends no dimension and the default stands in, which is the same bound the
 * deployed model routes apply anyway.
 */
@Serializable
data class ImageLimitsView(
    @SerialName("maxImageBytes") val maxImageBytes: Long = 3_670_016,
    @SerialName("maxImagesPerMessage") val maxImagesPerMessage: Int = 20,
    @SerialName("maxMessageImageBytes") val maxMessageImageBytes: Long = 104_857_600,
    @SerialName("maxImagePixels") val maxImagePixels: Long = 40_000_000,
    @SerialName("maxImageDimension") val maxImageDimension: Int = 2_000,
    @SerialName("mediaTypes") val mediaTypes: List<String> =
        listOf("image/png", "image/jpeg", "image/webp", "image/gif"),
) {
    /**
     * Why adding one more image would break the *message's* bounds, or null when it would not.
     *
     * Run before [admitImage], because that is the order `AttachmentStore.saveImages` uses: it
     * refuses the batch on count and then on aggregate size before it looks at any single member.
     * Matching the order matters — a refusal raised here has to name the same limit the host
     * would have named, or the message the user reads changes depending on who said no.
     *
     * @param pendingCount images already attached to this message, excluding the new one.
     * @param pendingBytes their total encoded size.
     * @param addedBytes the new image's encoded size.
     */
    fun admitBatch(pendingCount: Int, pendingBytes: Long, addedBytes: Int): ImageRejection? = when {
        pendingCount + 1 > maxImagesPerMessage -> ImageRejection.TOO_MANY
        pendingBytes + addedBytes > maxMessageImageBytes -> ImageRejection.BATCH_TOO_LARGE
        else -> null
    }

    /**
     * Why the host would refuse this one image, or null when it would take it.
     *
     * The sequence is the host's own — declared type, encoded size, decodability, then the two
     * decoded-size bounds, then the declared type against the bytes. `maxImagePixels` is checked
     * before `maxImageDimension` because `detectImage` checks them in that order, so an image
     * that breaks both is reported as a resolution problem on both sides.
     *
     * @param detectedMediaType what the bytes themselves decode as, or null when they did not
     *   decode at all; a mismatch against [declaredMediaType] is the host's `IMAGE_TYPE_MISMATCH`.
     */
    fun admitImage(
        declaredMediaType: String,
        detectedMediaType: String?,
        bytes: Int,
        width: Int,
        height: Int,
    ): ImageRejection? = when {
        declaredMediaType !in mediaTypes -> ImageRejection.UNSUPPORTED_TYPE
        bytes > maxImageBytes -> ImageRejection.TOO_LARGE
        bytes <= 0 || width <= 0 || height <= 0 -> ImageRejection.UNSUPPORTED_TYPE
        width.toLong() * height.toLong() > maxImagePixels -> ImageRejection.TOO_MANY_PIXELS
        maxOf(width, height) > maxImageDimension -> ImageRejection.DIMENSION_TOO_LARGE
        detectedMediaType != null && detectedMediaType != declaredMediaType ->
            ImageRejection.UNSUPPORTED_TYPE
        else -> null
    }
}

/** The `plan` projection: whether plan mode is on, and whether a plan is awaiting review. */
@Serializable
data class PlanStateView(
    @SerialName("active") val active: Boolean = false,
    @SerialName("pending") val pending: Boolean = false,
)

/** The `sessionListMetadata` projection: list-shaping facts the summary would otherwise repeat. */
@Serializable
data class SessionListMetadataView(
    @SerialName("blank") val blank: Boolean = true,
    @SerialName("lastPromptAt") val lastPromptAt: Long? = null,
)
