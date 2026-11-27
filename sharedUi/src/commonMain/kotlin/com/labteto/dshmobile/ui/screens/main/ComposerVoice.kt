package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Where the composer's dictation comes from. The host builds it: on Android it is the phone's speech service
 * (`DictationConfig` and `rememberComposerDictation` in `:app`, with the system's microphone dialog, the lifecycle
 * and the language sheet). iOS has no dictation yet and passes none, so its composer has no microphone.
 */
interface ComposerVoiceSource {
    /**
     * The dictation for one composer, or null when the platform has no recognition service.
     *
     * @param enabled false while the composer cannot send (no session); a running take is discarded.
     * @param onInsert puts recognised text into the draft at its caret. The only way text arrives.
     */
    @Composable
    fun rememberVoice(enabled: Boolean, onInsert: (spoken: String) -> Unit): ComposerVoice?

    /**
     * [draft] with the recognised [spoken] phrase put in at [cursor] as ordinary text, and the caret after it; null
     * when the phrase holds nothing. Where a space goes is the platform's call (Android's is script aware), so the
     * composer asks rather than joining the text itself.
     */
    fun insert(draft: String, cursor: Int, spoken: String): TextFieldValue?
}

/** What the composer draws and calls for dictation; the host rebuilds it whenever the dictation changes. */
@Immutable
class ComposerVoice(
    /** Discards the take in progress. A send calls it first, so a take's text cannot land in the next draft. */
    val cancel: () -> Unit,
    /**
     * What the field paints over [base] (the reference chips) while a take runs: the heard text at the caret,
     * not yet in the draft. Returns [base] when nothing is pending.
     */
    val pending: (base: VisualTransformation, draft: String, cursor: Int) -> VisualTransformation,
    /** Whether the strip under the message box has anything to say. */
    val stripVisible: Boolean,
    /** The microphone, in the message field's trailing slot (48dp; the row of controls has none to spare). */
    val micButton: @Composable () -> Unit,
    /** The line between the message box and the row of controls, while [stripVisible]. */
    val strip: @Composable () -> Unit,
)
