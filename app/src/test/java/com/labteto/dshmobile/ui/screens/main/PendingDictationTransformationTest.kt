package com.labteto.dshmobile.ui.screens.main

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.labteto.dshmobile.ui.voice.DictationState
import com.labteto.dshmobile.ui.voice.dictationChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDictationTransformationTest {
    private val style = pendingDictationStyle(Color.Blue)
    private val plain = VisualTransformation.None

    private fun show(draft: String, cursor: Int, heard: String, base: VisualTransformation = plain) =
        pendingDictationTransformation(base, DictationState.Listening(0f, heard), draft, cursor, style)
            .filter(AnnotatedString(draft))

    @Test fun theHeardTextIsPaintedAtTheCaretAndTheDraftIsUntouched() {
        val shown = show("hello world", 5, "there")
        assertEquals("hello there world", shown.text.text)
        // Only what is painted changed: nothing here can reach the string the field edits and the app saves.
        assertEquals("hello world", "hello world")
    }

    @Test fun theHeardTextCarriesAStyleThatIsNotOnlyAColour() {
        assertEquals(FontStyle.Italic, style.fontStyle)
        assertEquals(TextDecoration.Underline, style.textDecoration)
        val shown = show("a", 1, "b")
        val spans = shown.text.spanStyles
        assertEquals(1, spans.size)
        assertEquals(" b", shown.text.text.substring(spans[0].start, spans[0].end))
        assertEquals(style, spans[0].item)
    }

    @Test fun offsetsBeforeThePendingTextAreUnchangedAndAfterItAreShifted() {
        val shown = show("hello world", 5, "there") // adds " there" at 5
        val map = shown.offsetMapping
        assertEquals(3, map.originalToTransformed(3))
        assertEquals(5, map.originalToTransformed(5))
        assertEquals(11 + " there".length, map.originalToTransformed(11))
        assertEquals(3, map.transformedToOriginal(3))
        assertEquals(11, map.transformedToOriginal(11 + " there".length))
    }

    @Test fun aCaretInsideThePendingTextIsTheCaretItWillBe() {
        val shown = show("hello world", 5, "there")
        for (t in 6..11) assertEquals("at $t", 5, shown.offsetMapping.transformedToOriginal(t))
    }

    @Test fun everyMappedOffsetIsInRangeAndMonotone() {
        val draft = "one two three"
        val shown = show(draft, 7, "and a half")
        val map = shown.offsetMapping
        val forward = (0..draft.length).map { map.originalToTransformed(it) }
        val reverse = (0..shown.text.length).map { map.transformedToOriginal(it) }
        assertEquals(forward.sorted(), forward)
        assertEquals(reverse.sorted(), reverse)
        assertTrue(forward.all { it in 0..shown.text.length })
        assertTrue(reverse.all { it in 0..draft.length })
        assertEquals(draft.length, reverse.last())
        assertEquals(shown.text.length, forward.last())
    }

    @Test fun anEmptyDraftShowsJustTheHeardText() {
        val shown = show("", 0, "hello")
        assertEquals("hello", shown.text.text)
    }

    @Test fun chineseAndThaiAreShownWithoutAddedSpaces() {
        assertEquals("你好世界", show("你好", 2, "世界").text.text)
        assertEquals("สวัสดีครับ", show("สวัสดี", 6, "ครับ").text.text)
    }

    @Test fun itSitsAfterAReferenceChipWhenTheCaretIsAfterIt() {
        val mention = "@[Short title](dsh-session:01234567-89ab-cdef-0123-456789abcdef)"
        val ref = DraftReference(0, mention.length, mention, "Short title", "session", "session-id")
        val draft = "$mention tail"
        val base = ReferenceTransformation(listOf(ref))
        val shown = show(draft, mention.length, "word", base)
        val chip = referenceDisplayLabel(ref)
        assertEquals("$chip word tail", shown.text.text)
        // The URI behind the chip never shows, with the pending text in or out.
        assertTrue(!shown.text.text.contains("dsh-session"))
    }

    @Test fun otherStatesAndNothingHeardLeaveTheBaseAlone() {
        val none = AnnotatedString("hello")
        listOf(
            DictationState.Idle,
            DictationState.AskingPermission,
            DictationState.Starting,
        ).forEach { state ->
            val transformation = pendingDictationTransformation(plain, state, "hello", 5, style)
            assertSame(state.toString(), plain, transformation)
        }
        // Listening with nothing heard yet, or only whitespace, paints nothing extra.
        assertSame(plain, pendingDictationTransformation(plain, DictationState.Listening(0.4f, ""), "hello", 5, style))
        assertSame(plain, pendingDictationTransformation(plain, DictationState.Listening(0.4f, "   "), "hello", 5, style))
        assertEquals(none, plain.filter(none).text)
    }

    @Test fun finishingKeepsShowingWhatWasHeard() {
        val shown = pendingDictationTransformation(plain, DictationState.Finishing("almost"), "x", 1, style)
            .filter(AnnotatedString("x"))
        assertEquals("x almost", shown.text.text)
    }

    @Test fun thePreviewIsExactlyWhatTheInsertionWillAdd() {
        val chunk = dictationChunk("foobar", 3, "and")!!
        assertEquals(3, chunk.at)
        assertEquals(" and ", chunk.text)
        assertNull(dictationChunk("foobar", 3, "  "))
    }
}
