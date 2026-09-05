package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Slash-command DTOs, ported from `packages/interaction/commands/src/types.ts` (v0.1.3-alpha.1).
 *
 * The catalog is read through the typert remote `commands/list`; it is per-session and
 * deployment-dependent (a preset switch changes which commands an agent resolves), so it is
 * never hardcoded on the client.
 */

/** A command that takes a trailing argument, plus the hint a client shows for it. */
@Serializable
data class CommandInputDescriptor(
    @SerialName("hint") val hint: String = "",
    /**
     * Whether composer attachments — images and, since harness 0.1.3, files — may accompany an
     * invocation. Through 0.1.2 the key was `images`; 0.1.3 renamed it when files joined, and a
     * 0.1.3 host sends no `images` key at all. False means the executor refuses an invocation
     * carrying any, so a capable composer refuses the submission before dispatch rather than
     * quietly sending the line to the model instead.
     */
    @SerialName("attachments") val attachments: Boolean = false,
)

/**
 * One entry of a session's command catalog. [input] is null for a bare command, which a client
 * may execute straight from the menu; a command with [input] prefills the composer instead.
 */
@Serializable
data class CommandDescriptor(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String = "",
    @SerialName("input") val input: CommandInputDescriptor? = null,
) {
    /** The line a bare invocation submits. */
    val line: String get() = "/$name"

    /** The draft prefix an argument-taking invocation prefills. */
    val draftPrefix: String get() = "/$name "

    /** Whether this command accepts composer attachments (`/goal` and `/plan`). */
    val acceptsAttachments: Boolean get() = input?.attachments == true
}

/**
 * One base64-encoded image riding a wire request — the harness's `EncodedImageAttachment`
 * (`packages/attachment/attachment/src/types.ts`).
 *
 * Deliberately not [PromptContentPart.Image]: that shape carries a `type` discriminator the
 * prompt codec declares, and this one is the discriminator-less form. Since 0.1.3 a command
 * carries attachments as [CommandSubmitAttachment], which *does* carry one — see [asSubmit].
 */
@Serializable
data class EncodedImageAttachment(
    @SerialName("mediaType") val mediaType: String,
    /** Canonical base64 of the image bytes; the host verifies it against the declared type. */
    @SerialName("data") val data: String,
    /** Optional display name; never interpreted as a path. */
    @SerialName("name") val name: String? = null,
) {
    /** The same image in the shape `commands/execute` takes. */
    fun asSubmit(): CommandSubmitAttachment = CommandSubmitAttachment.Image(mediaType, data, name)
}

/**
 * One attachment submitted with a slash command — `CommandSubmitAttachment` upstream.
 *
 * Images travel as bytes, exactly as a prompt carries them. A file travels as the receipt a
 * preceding upload on the same session returned (see `DshApiClient.uploadFileBinary`); the
 * executor resolves it back to the stored file, and refuses a receipt it did not mint for this
 * session.
 */
@Serializable
sealed class CommandSubmitAttachment {
    @Serializable
    @SerialName("image")
    data class Image(
        @SerialName("mediaType") val mediaType: String,
        @SerialName("data") val data: String,
        @SerialName("name") val name: String? = null,
    ) : CommandSubmitAttachment()

    @Serializable
    @SerialName("file")
    data class File(
        @SerialName("receiptId") val receiptId: String,
    ) : CommandSubmitAttachment()
}
