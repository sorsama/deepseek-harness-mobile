package com.labteto.dshmobile.ui.screens.main

import android.content.Context
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.ImageLimitsView
import com.labteto.dshmobile.core.wire.dto.ImageRejection
import java.util.Locale

/**
 * What to tell the user when an image will not go.
 *
 * The port of the harness's own `attachmentErrorText` (`packages/client/ui-conversation/src/
 * client/image-labels.ts`). One table serves both sides of the refusal — the check this client
 * runs before uploading and the `attachment-error` a host still answers with — so a rejection
 * reads the same either way, and a limit the client has not learned about yet still names itself.
 */

/**
 * Byte count as user-facing megabytes (`10MB`, `2.5MB`) — the harness's `imageSizeText`.
 *
 * The decimal separator is fixed rather than localised: this is a limit marker the user may well
 * be comparing against what the harness itself wrote, and the two have to look like one number.
 */
internal fun imageSizeText(bytes: Long): String {
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb == Math.floor(mb)) "${mb.toLong()}MB" else String.format(Locale.US, "%.1fMB", mb)
}

/**
 * Product copy for one refusal.
 *
 * A reason whose copy names a limit falls back to the generic line when no limits are known, the
 * same way the harness does: a sentence with a hole in it is worse than a vaguer sentence.
 *
 * @param limits the session's `imageLimits` projection, when it has arrived.
 * @param rawReason the wire `details.reason` when the refusal came from the host, so an
 *   [ImageRejection.UNKNOWN] can still carry something a bug report can act on.
 */
internal fun imageRejectionText(
    context: Context,
    rejection: ImageRejection,
    limits: ImageLimitsView?,
    rawReason: String? = null,
): String = when (rejection) {
    ImageRejection.UNSUPPORTED_TYPE -> context.getString(R.string.err_image_unsupported_type)
    ImageRejection.MODEL_UNSUPPORTED -> context.getString(R.string.err_image_model_unsupported)
    ImageRejection.SUBAGENT_UNSUPPORTED -> context.getString(R.string.err_image_subagent_unsupported)
    ImageRejection.TOO_MANY_PIXELS -> context.getString(R.string.err_image_pixels)
    ImageRejection.TOO_LARGE -> limits
        ?.let { context.getString(R.string.err_image_too_large, imageSizeText(it.maxImageBytes)) }
        ?: sendFailed(context, rawReason)
    ImageRejection.BATCH_TOO_LARGE -> limits
        ?.let { context.getString(R.string.err_image_batch_too_large, imageSizeText(it.maxMessageImageBytes)) }
        ?: sendFailed(context, rawReason)
    ImageRejection.TOO_MANY -> limits
        ?.let { context.getString(R.string.err_image_too_many, it.maxImagesPerMessage) }
        ?: sendFailed(context, rawReason)
    ImageRejection.DIMENSION_TOO_LARGE -> limits
        ?.let { context.getString(R.string.err_image_dimension, it.maxImageDimension) }
        ?: sendFailed(context, rawReason)
    ImageRejection.UNKNOWN -> sendFailed(context, rawReason)
}

private fun sendFailed(context: Context, rawReason: String?): String =
    context.getString(R.string.err_image_send_failed, rawReason ?: "unknown")
