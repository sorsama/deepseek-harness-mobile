package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.labteto.dshmobile.core.wire.dto.ImageLimitsView
import com.labteto.dshmobile.core.wire.dto.ImageRejection
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.err_file_not_staged
import com.labteto.dshmobile.shared.resources.err_file_subagent_unsupported
import com.labteto.dshmobile.shared.resources.err_image_batch_too_large
import com.labteto.dshmobile.shared.resources.err_image_dimension
import com.labteto.dshmobile.shared.resources.err_image_model_unsupported
import com.labteto.dshmobile.shared.resources.err_image_pixels
import com.labteto.dshmobile.shared.resources.err_image_send_failed
import com.labteto.dshmobile.shared.resources.err_image_send_failed_plain
import com.labteto.dshmobile.shared.resources.err_image_subagent_unsupported
import com.labteto.dshmobile.shared.resources.err_image_too_large
import com.labteto.dshmobile.shared.resources.err_image_too_many
import com.labteto.dshmobile.shared.resources.err_image_unsupported_type
import com.labteto.dshmobile.ui.components.fillTemplate
import com.labteto.dshmobile.ui.components.oneDecimal
import com.labteto.dshmobile.ui.components.rememberCountFormat
import org.jetbrains.compose.resources.stringResource

/**
 * What to tell the user when an image will not go.
 *
 * The port of the harness's own `attachmentErrorText` (`packages/client/ui-conversation/src/
 * client/image-labels.ts`). One table serves both sides of the refusal — the check this client
 * runs before uploading and the `session/attachment-invalid` a host still answers with — so a
 * rejection reads the same either way, and a limit the client has not learned about yet still
 * names itself.
 */

/**
 * Byte count as user-facing megabytes (`10MB`, `2.5MB`) — the harness's `imageSizeText`.
 *
 * The decimal separator is fixed rather than localised: this is a limit marker the user may well
 * be comparing against what the harness itself wrote, and the two have to look like one number.
 */
internal fun imageSizeText(bytes: Long): String {
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb == kotlin.math.floor(mb)) "${mb.toLong()}MB" else oneDecimal(mb) + "MB"
}

/**
 * The refusal copy in the reader's language, read while composing and used later: a refusal is written when a call
 * comes back, or while picked images are read off the main thread, where no string resource can be looked up. Each
 * line is the Android string resource of the same name, filled as Android's `getString(id, args)` fills it
 * ([fillTemplate]; a count in the reader's own digits).
 */
class ImageErrorCopy internal constructor(
    internal val unsupportedType: String,
    internal val modelUnsupported: String,
    internal val subagentUnsupported: String,
    internal val pixels: String,
    internal val tooLarge: String,
    internal val batchTooLarge: String,
    internal val tooMany: String,
    internal val dimension: String,
    internal val fileNotStaged: String,
    internal val fileSubagentUnsupported: String,
    internal val sendFailedPlain: String,
    internal val sendFailed: String,
    internal val count: (Int) -> String,
)

/** The [ImageErrorCopy] of the language this composition is in. */
@Composable
fun rememberImageErrorCopy(): ImageErrorCopy {
    val unsupportedType = stringResource(Res.string.err_image_unsupported_type)
    val modelUnsupported = stringResource(Res.string.err_image_model_unsupported)
    val subagentUnsupported = stringResource(Res.string.err_image_subagent_unsupported)
    val pixels = stringResource(Res.string.err_image_pixels)
    val tooLarge = stringResource(Res.string.err_image_too_large)
    val batchTooLarge = stringResource(Res.string.err_image_batch_too_large)
    val tooMany = stringResource(Res.string.err_image_too_many)
    val dimension = stringResource(Res.string.err_image_dimension)
    val fileNotStaged = stringResource(Res.string.err_file_not_staged)
    val fileSubagentUnsupported = stringResource(Res.string.err_file_subagent_unsupported)
    val sendFailedPlain = stringResource(Res.string.err_image_send_failed_plain)
    val sendFailed = stringResource(Res.string.err_image_send_failed)
    val count = rememberCountFormat()
    return remember(
        unsupportedType, modelUnsupported, subagentUnsupported, pixels, tooLarge, batchTooLarge, tooMany, dimension,
        fileNotStaged, fileSubagentUnsupported, sendFailedPlain, sendFailed, count,
    ) {
        ImageErrorCopy(
            unsupportedType, modelUnsupported, subagentUnsupported, pixels, tooLarge, batchTooLarge, tooMany, dimension,
            fileNotStaged, fileSubagentUnsupported, sendFailedPlain, sendFailed, count,
        )
    }
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
fun imageRejectionText(
    copy: ImageErrorCopy,
    rejection: ImageRejection,
    limits: ImageLimitsView?,
    rawReason: String? = null,
): String = when (rejection) {
    ImageRejection.UNSUPPORTED_TYPE -> copy.unsupportedType
    ImageRejection.MODEL_UNSUPPORTED -> copy.modelUnsupported
    ImageRejection.SUBAGENT_UNSUPPORTED -> copy.subagentUnsupported
    ImageRejection.TOO_MANY_PIXELS -> copy.pixels
    ImageRejection.TOO_LARGE -> limits
        ?.let { fillTemplate(copy.tooLarge, imageSizeText(it.maxImageBytes)) }
        ?: sendFailed(copy, rawReason)
    ImageRejection.BATCH_TOO_LARGE -> limits
        ?.let { fillTemplate(copy.batchTooLarge, imageSizeText(it.maxMessageImageBytes)) }
        ?: sendFailed(copy, rawReason)
    ImageRejection.TOO_MANY -> limits
        ?.let { fillTemplate(copy.tooMany, copy.count(it.maxImagesPerMessage)) }
        ?: sendFailed(copy, rawReason)
    ImageRejection.DIMENSION_TOO_LARGE -> limits
        ?.let { fillTemplate(copy.dimension, copy.count(it.maxImageDimension)) }
        ?: sendFailed(copy, rawReason)
    // Files (harness 0.1.3). A spent or foreign receipt means the upload has to happen again;
    // there is nothing in the bytes to correct, so the copy says to re-attach rather than resize.
    ImageRejection.FILE_NOT_STAGED -> copy.fileNotStaged
    ImageRejection.SUBAGENT_FILE_UNSUPPORTED -> copy.fileSubagentUnsupported
    ImageRejection.UNKNOWN -> sendFailed(copy, rawReason)
}

private fun sendFailed(copy: ImageErrorCopy, rawReason: String?): String =
    if (rawReason.isNullOrBlank()) {
        copy.sendFailedPlain
    } else {
        fillTemplate(copy.sendFailed, rawReason)
    }
