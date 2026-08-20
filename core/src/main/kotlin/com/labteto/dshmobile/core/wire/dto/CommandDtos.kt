package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Slash-command DTOs, ported from `packages/interaction/commands/src/types.ts` (v0.1.0-rc.8).
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
     * Whether composer image attachments may accompany an invocation (harness 0.1.0-rc.8; an
     * rc.7 host sends no such key and the default stands). False means the executor refuses an
     * invocation carrying images, so a capable composer refuses the submission before dispatch
     * rather than quietly sending the line to the model instead.
     */
    @SerialName("images") val images: Boolean = false,
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

    /** Whether this command accepts composer images (harness 0.1.0-rc.8; `/goal` and `/plan`). */
    val acceptsImages: Boolean get() = input?.images == true
}

/**
 * One base64-encoded image riding a wire request — the harness's `EncodedImageAttachment`
 * (`packages/attachment/attachment/src/types.ts`).
 *
 * Deliberately not [PromptContentPart.Image]: that shape carries a `type` discriminator the
 * command gateway's boundary codec does not declare, and the gateway validates its arguments
 * against the declared shape rather than ignoring what it did not ask for.
 */
@Serializable
data class EncodedImageAttachment(
    @SerialName("mediaType") val mediaType: String,
    /** Canonical base64 of the image bytes; the host verifies it against the declared type. */
    @SerialName("data") val data: String,
    /** Optional display name; never interpreted as a path. */
    @SerialName("name") val name: String? = null,
)
