package com.labteto.dshmobile.ui.screens.main

import androidx.compose.ui.graphics.ImageBitmap
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.FileAttachmentRef

/**
 * Where a picked file is read from: `android.net.Uri` on Android. Only a type here, so a draft and its
 * attachments can live in shared code while the pickers stay in the Android app. Abstract, with an abstract
 * [toString], because `Uri` is declared that way and an actual must match its expect member for member.
 * iOS has no file picker yet (attachments come after the chat, issue #141), so nothing builds one there.
 */
expect abstract class AttachmentUri {
    abstract override fun toString(): String
}

/**
 * Something picked and waiting to be sent with the next message.
 *
 * Two kinds, because the harness carries them two ways. An [Image] rides the prompt as bytes,
 * so it is held here fully encoded. A [File] is uploaded the moment it is picked — harness
 * 0.1.3 streams it to the host verbatim — and what the prompt carries is the receipt that upload
 * answered with, so the chip tracks the upload rather than the bytes.
 */
sealed interface PendingAttachment {
    /** Display name, when the picker had one. */
    val name: String?

    /**
     * A picked image, held with its decoded preview.
     *
     * [bytes], [width] and [height] are the encoded size and intrinsic dimensions the picker already
     * had to learn to admit the image; keeping them means the *next* pick can be measured against
     * the message's running totals without decoding everything already attached a second time.
     */
    data class Image(
        val mediaType: String,
        val base64: String,
        val preview: ImageBitmap?,
        val bytes: Int,
        val width: Int,
        val height: Int,
        override val name: String? = null,
    ) : PendingAttachment {
        /** The wire form `session/prompt` carries. */
        fun encoded(): EncodedImageAttachment = EncodedImageAttachment(mediaType, base64, name)
    }

    /** A picked file and the state of its upload. [id] is what a progress callback keys on. */
    data class File(
        val id: String,
        val uri: AttachmentUri,
        override val name: String,
        /** Size as the provider reported it; `-1` when it would not say. */
        val size: Long,
        val state: FileUploadState,
    ) : PendingAttachment {
        /** The receipt to cite, once the upload has one. */
        val receiptId: String? get() = (state as? FileUploadState.Ready)?.receiptId
    }
}

/** Where one file's upload stands. */
sealed interface FileUploadState {
    /** Bytes are still going up; [sent] is the running count. */
    data class Uploading(val sent: Long) : FileUploadState

    /** The host holds the file and minted a receipt for it. */
    data class Ready(val receiptId: String, val file: FileAttachmentRef) : FileUploadState

    /** The upload did not complete; the chip offers a retry. */
    data class Failed(val message: String) : FileUploadState
}
