package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.wire.dto.CommandDescriptor

/**
 * What the composer's send button should actually do with the draft.
 *
 * A slash line is not a prompt: `session.prompt` hands its content straight to the model, so a
 * command has to be recognised here and written through `commands/execute` instead.
 */
internal sealed interface Submission {
    /** A registered command line; goes to the command gateway, never to the model. */
    data class Command(val line: String) : Submission

    /** Ordinary user text — including a `/skill-name` line, which the host resolves itself. */
    data class Prompt(val text: String) : Submission

    /**
     * The line names a command that cannot take the attached images. Nothing is sent: the draft
     * and the images both stay in the composer behind a notice, which is what the harness's own
     * client does — a submission that cannot be honoured whole is not quietly reshaped into one
     * that can.
     */
    data class Refused(val command: String, val reason: RefusalReason) : Submission
}

/** Why a command line refused the composer's attachments. */
internal enum class RefusalReason {
    /** The command itself declares no image acceptance — most of them. */
    COMMAND_TAKES_NO_IMAGES,

    /** The command would take them, but this harness predates the wire that carries them. */
    HOST_TOO_OLD,
}

/**
 * Decide whether a draft is a command or a prompt — the port of the harness web client's
 * `matchEnter` (`packages/client/ui-commands/src/client/service.ts`).
 *
 * The order matters, and the misses matter as much as the hits: a `/` line that names nothing in
 * the catalog falls through to [Submission.Prompt] on purpose, because that is how skills are
 * invoked — the host's pre-step boundary recognises the leading `/name` and injects the skill body.
 * Adjudicating against the catalog first is what keeps a skill that shares a name with a command
 * resolving to the command.
 *
 * Images are adjudicated here for the same reason the harness adjudicates them in its composer:
 * the host refuses them per command, and a refusal that arrived after dispatch would have already
 * cost the user their draft. Before harness 0.1.0-rc.8 no command took images at all and this
 * client silently demoted such a line to a prompt — which sent `/goal` to the model as the literal
 * text `/goal`, with nothing on screen to say so.
 *
 * @param draft the composer's raw text.
 * @param catalog the session's command catalog (`commands/list`); empty means every line is a prompt.
 * @param attachments how many images ride along.
 * @param hostAcceptsImages whether this harness's `commands/execute` carries images at all
 *   (`DshApiClient.acceptsCommandImages`).
 */
internal fun adjudicate(
    draft: String,
    catalog: List<CommandDescriptor>,
    attachments: Int,
    hostAcceptsImages: Boolean,
): Submission {
    val trimmed = draft.trim()
    if (!trimmed.startsWith("/")) return Submission.Prompt(draft)

    val separator = trimmed.indexOfFirst { it.isWhitespace() }
    val bare = separator == -1
    val name = (if (bare) trimmed else trimmed.substring(0, separator)).substring(1)
    if (name.isEmpty()) return Submission.Prompt(draft)

    // Case-sensitive on purpose: the host parses command names with `^/([a-z][a-z0-9_-]*)`, so a
    // catalog name is always lowercase and `/Compact` is not a command.
    val descriptor = catalog.firstOrNull { it.name == name } ?: return Submission.Prompt(draft)

    // An argument-taking command claims the whole line; one that takes none only answers to a bare
    // invocation, so `/compact and then some` stays a prompt rather than silently dropping the tail.
    if (descriptor.input == null && !bare) return Submission.Prompt(draft)

    if (attachments > 0) {
        if (!descriptor.acceptsImages) return Submission.Refused(name, RefusalReason.COMMAND_TAKES_NO_IMAGES)
        if (!hostAcceptsImages) return Submission.Refused(name, RefusalReason.HOST_TOO_OLD)
    }
    return Submission.Command(trimmed)
}
