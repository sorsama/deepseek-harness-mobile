package com.labteto.dshmobile.ui.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Enter and its chords mean in the message box.
 *
 * The rule has two halves that must not leak into each other: with a hardware keyboard Enter sends
 * and Shift+Enter adds a line; on a phone, and for anything the on-screen keyboard produces, nothing
 * here may answer, because the return key adding a line is what a phone has always done.
 */
class ComposerKeysTest {

    private fun enter(
        down: Boolean = true,
        repeat: Boolean = false,
        shift: Boolean = false,
        ctrl: Boolean = false,
        meta: Boolean = false,
        alt: Boolean = false,
        soft: Boolean = false,
    ) = ComposerKeyPress(isEnter = true, isDown = down, isRepeat = repeat, shift = shift, ctrl = ctrl, meta = meta, alt = alt, fromSoftKeyboard = soft)

    private fun other() = enter().copy(isEnter = false)

    @Test fun enterSendsWithAHardwareKeyboard() {
        assertEquals(ComposerKeyOutcome.Send, decideComposerKey(enter(), hardwareKeyboard = true))
    }

    @Test fun shiftEnterAddsALine() {
        assertEquals(ComposerKeyOutcome.Newline, decideComposerKey(enter(shift = true), hardwareKeyboard = true))
    }

    @Test fun altEnterAddsALineToo() {
        assertEquals(ComposerKeyOutcome.Newline, decideComposerKey(enter(alt = true), hardwareKeyboard = true))
    }

    @Test fun ctrlEnterAndCmdEnterSend() {
        assertEquals(ComposerKeyOutcome.Send, decideComposerKey(enter(ctrl = true), hardwareKeyboard = true))
        assertEquals(ComposerKeyOutcome.Send, decideComposerKey(enter(meta = true), hardwareKeyboard = true))
        // Ctrl wins over Shift: a chord that includes it is the send chord.
        assertEquals(ComposerKeyOutcome.Send, decideComposerKey(enter(ctrl = true, shift = true), hardwareKeyboard = true))
    }

    @Test fun ctrlEnterSendsEvenWhereNoKeyboardIsReported() {
        // It always did: the chord is unambiguous either way, and removing it would be a regression.
        assertEquals(ComposerKeyOutcome.Send, decideComposerKey(enter(ctrl = true), hardwareKeyboard = false))
    }

    @Test fun aPhoneKeepsItsReturnKey() {
        // No keyboard attached: plain, Shift and Alt Enter are all left to the field, which adds a line.
        assertEquals(ComposerKeyOutcome.Default, decideComposerKey(enter(), hardwareKeyboard = false))
        assertEquals(ComposerKeyOutcome.Default, decideComposerKey(enter(shift = true), hardwareKeyboard = false))
        assertEquals(ComposerKeyOutcome.Default, decideComposerKey(enter(alt = true), hardwareKeyboard = false))
    }

    @Test fun theOnScreenKeyboardIsNeverAnswered() {
        // A keyboard may be attached while the soft one is also showing; its return key still adds a line.
        assertEquals(ComposerKeyOutcome.Default, decideComposerKey(enter(soft = true), hardwareKeyboard = true))
        assertEquals(ComposerKeyOutcome.Default, decideComposerKey(enter(shift = true, soft = true), hardwareKeyboard = true))
    }

    @Test fun otherKeysAreNotOurs() {
        assertEquals(ComposerKeyOutcome.Default, decideComposerKey(other(), hardwareKeyboard = true))
        assertEquals(ComposerKeyOutcome.Default, decideComposerKey(other().copy(ctrl = true), hardwareKeyboard = true))
    }

    @Test fun theReleaseIsSwallowedSoItCannotLeakALineBreak() {
        assertEquals(ComposerKeyOutcome.Swallow, decideComposerKey(enter(down = false), hardwareKeyboard = true))
        assertEquals(ComposerKeyOutcome.Swallow, decideComposerKey(enter(down = false, shift = true), hardwareKeyboard = true))
        assertEquals(ComposerKeyOutcome.Swallow, decideComposerKey(enter(down = false, ctrl = true), hardwareKeyboard = true))
    }

    @Test fun aHeldEnterSendsOnce() {
        assertEquals(ComposerKeyOutcome.Swallow, decideComposerKey(enter(repeat = true), hardwareKeyboard = true))
        assertEquals(ComposerKeyOutcome.Swallow, decideComposerKey(enter(repeat = true, ctrl = true), hardwareKeyboard = true))
    }

    @Test fun aHeldShiftEnterKeepsAddingLines() {
        assertEquals(ComposerKeyOutcome.Newline, decideComposerKey(enter(repeat = true, shift = true), hardwareKeyboard = true))
    }

    // ---- insertNewline -------------------------------------------------------------------------

    @Test fun newlineGoesAtTheCaret() {
        val result = insertNewline(TextFieldValue("hello world", TextRange(5)))
        assertEquals("hello\n world", result.text)
        assertEquals(TextRange(6), result.selection)
    }

    @Test fun newlineReplacesTheSelection() {
        val result = insertNewline(TextFieldValue("hello world", TextRange(5, 11)))
        assertEquals("hello\n", result.text)
        assertEquals(TextRange(6), result.selection)
    }

    @Test fun newlineWorksWithABackwardSelectionAndAtTheEnds() {
        assertEquals("a\nc", insertNewline(TextFieldValue("abc", TextRange(2, 1))).text)
        assertEquals("\nabc", insertNewline(TextFieldValue("abc", TextRange(0))).text)
        assertEquals("abc\n", insertNewline(TextFieldValue("abc", TextRange(3))).text)
        assertEquals("\n", insertNewline(TextFieldValue("")).text)
    }
}
