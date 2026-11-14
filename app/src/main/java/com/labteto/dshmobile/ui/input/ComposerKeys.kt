package com.labteto.dshmobile.ui.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/*
 * What a key press means in the message box.
 *
 * Kept as plain functions of plain values so the rule can be tested without a keyboard: the
 * composer reads the platform key event into a [ComposerKeyPress], asks [decideComposerKey], and
 * acts on the answer. The rule exists because the same Enter key means two things:
 *
 *  - On a touch screen the on-screen return key adds a line, and the send button sends. A phone
 *    must keep doing that, so nothing from the soft keyboard is ever answered here.
 *  - With a hardware keyboard (a Chromebook, a tablet with a dock, a Bluetooth keyboard on a phone)
 *    people expect a chat box to work like every other one: Enter sends, Shift+Enter adds a line.
 */

/** One key event, reduced to what the decision needs. */
data class ComposerKeyPress(
    val isEnter: Boolean,
    /** False for the release; the action happens on the press. */
    val isDown: Boolean,
    /** True for the auto-repeats a held key produces after the first press. */
    val isRepeat: Boolean,
    val shift: Boolean,
    val ctrl: Boolean,
    val meta: Boolean,
    val alt: Boolean,
    /** True when an on-screen keyboard produced the event (`KeyEvent.FLAG_SOFT_KEYBOARD`). */
    val fromSoftKeyboard: Boolean,
)

/** What the composer does with a [ComposerKeyPress]. */
enum class ComposerKeyOutcome {
    /** Send the draft, if it can be sent now. */
    Send,

    /** Insert a line break at the caret, replacing the selection. */
    Newline,

    /** The key was ours and is already handled (its release, or a held key repeating): do nothing. */
    Swallow,

    /** Not ours: the text field and the IME treat it as they always did. */
    Default,
}

/**
 * Decides what [press] does.
 *
 * Ctrl+Enter and Cmd+Enter send wherever there is a keyboard to press them on, as they always have.
 * Plain Enter sends, and Shift+Enter or Alt+Enter adds a line, but only when [hardwareKeyboard] says
 * one is attached and the event did not come from the on-screen keyboard: on a phone the return key
 * keeps adding a line, whatever else is going on.
 *
 * Sending acts on the press and swallows the release and the repeats of a held key, so holding
 * Enter sends once and the release cannot reach the field as a stray line break.
 */
fun decideComposerKey(press: ComposerKeyPress, hardwareKeyboard: Boolean): ComposerKeyOutcome {
    if (!press.isEnter) return ComposerKeyOutcome.Default
    val chord = press.ctrl || press.meta
    val physical = hardwareKeyboard && !press.fromSoftKeyboard
    val action = when {
        chord -> ComposerKeyOutcome.Send
        !physical -> return ComposerKeyOutcome.Default
        press.shift || press.alt -> ComposerKeyOutcome.Newline
        else -> ComposerKeyOutcome.Send
    }
    return when {
        !press.isDown -> ComposerKeyOutcome.Swallow
        action == ComposerKeyOutcome.Send && press.isRepeat -> ComposerKeyOutcome.Swallow
        else -> action
    }
}

/** [value] with its selection replaced by a line break and the caret after it. */
fun insertNewline(value: TextFieldValue): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(0, value.text.length)
    return value.copy(
        text = value.text.replaceRange(start, end, "\n"),
        selection = TextRange(start + 1),
        composition = null,
    )
}
