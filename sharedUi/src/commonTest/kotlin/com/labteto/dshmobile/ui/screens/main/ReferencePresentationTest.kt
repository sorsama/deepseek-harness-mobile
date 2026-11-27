package com.labteto.dshmobile.ui.screens.main

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class ReferencePresentationTest {
    private val mention = "@[Short title](dsh-session:01234567-89ab-cdef-0123-456789abcdef)"
    private fun reference(start: Int = 0) = DraftReference(start, start + mention.length, mention, "Short title", "session", "session-id")

    @Test fun `session URI is hidden behind a friendly label without changing storage`() {
        val raw = "Read $mention please"
        val ref = reference(5)
        val shown = ReferenceTransformation(listOf(ref)).filter(AnnotatedString(raw))
        assertEquals("Read \u2066@Short title\u2069 please", shown.text.text)
        assertFalse(shown.text.text.contains("dsh-session"))
        assertEquals(mention, raw.substring(ref.start, ref.end))
    }

    @Test fun `all mapped cursor offsets are valid and monotone and tokens are atomic`() {
        val raw = "😀 $mention tail"
        val ref = reference(3)
        val shown = ReferenceTransformation(listOf(ref)).filter(AnnotatedString(raw))
        val forward = (0..raw.length).map { shown.offsetMapping.originalToTransformed(it) }
        val reverse = (0..shown.text.length).map { shown.offsetMapping.transformedToOriginal(it) }
        assertEquals(forward.sorted(), forward)
        assertEquals(reverse.sorted(), reverse)
        assertTrue(forward.all { it in 0..shown.text.length })
        assertTrue(reverse.all { it in 0..raw.length })
        assertTrue(reverse.none { it > ref.start && it < ref.end })
        assertEquals(raw.length, reverse.last())
        assertEquals(shown.text.length, forward.last())
    }

    @Test fun `backspace at token end deletes the complete wire mention`() {
        val raw = "Read $mention tail"
        val ref = reference(5)
        val edited = atomicReferenceEdit(raw, TextFieldValue(raw.removeRange(ref.end - 1, ref.end), TextRange(ref.end - 1)), listOf(ref))
        assertEquals("Read  tail", edited.text)
        assertEquals(TextRange(5), edited.selection)
    }

    @Test fun `partial selection replacement removes all touched token text`() {
        val raw = "Read $mention tail"
        val ref = reference(5)
        val edited = atomicReferenceEdit(raw, TextFieldValue(raw.replaceRange(8, 12, "new"), TextRange(11)), listOf(ref))
        assertEquals("Read new tail", edited.text)
        assertEquals(TextRange(8), edited.selection)
    }

    @Test fun `typing beside a reference inserts a separator and preserves draft tracking`() {
        val raw = mention + " tail"
        val ref = reference()
        val next = raw.substring(0, ref.end) + "x" + raw.substring(ref.end)
        val edited = atomicReferenceEdit(raw, TextFieldValue(next, TextRange(ref.end + 1)), listOf(ref))
        assertEquals("$mention x tail", edited.text)
        assertEquals(listOf(ref), adjustReferences(raw, edited.text, listOf(ref)))
    }

    @Test fun `deleting a separator never reveals an orphan URI`() {
        val raw = "Read $mention tail"
        val ref = reference(5)
        val edited = atomicReferenceEdit(raw, TextFieldValue(raw.removeRange(4, 5), TextRange(4)), listOf(ref))
        assertEquals(raw, edited.text)
        assertEquals(listOf(ref), adjustReferences(raw, edited.text, listOf(ref)))
    }

    @Test fun `reopened draft presents references and ordinary text edits keep them`() {
        val ref = reference(3)
        val raw = "😀 $mention tail"
        val draft = ComposerDraft(ComposerKey("h", "s"), SavedComposer(raw, listOf(ref)))
        val edited = atomicReferenceEdit(raw, TextFieldValue("Hi $raw", TextRange(3)), draft.references)
        draft.text = edited.text
        assertEquals("Hi 😀 \u2066@Short title\u2069 tail", ReferenceTransformation(draft.references).filter(AnnotatedString(draft.text)).text.text)
        assertEquals(mention, draft.references.single().mention)
    }

    @Test fun `multiple tokens map their boundaries and untouched text independently`() {
        val first = reference()
        val second = reference(mention.length + 1)
        val raw = "$mention $mention!"
        val display = ReferenceTransformation(listOf(first, second)).filter(AnnotatedString(raw))
        assertEquals("\u2066@Short title\u2069 \u2066@Short title\u2069!", display.text.text)
        val end = referenceDisplayLabel(first).length
        assertEquals(first.end, display.offsetMapping.transformedToOriginal(end))
        assertEquals(second.start, display.offsetMapping.transformedToOriginal(end + 1))
        assertEquals(raw.length, display.offsetMapping.transformedToOriginal(display.text.length))
    }

    @Test fun `RTL display isolates adjacent mentions without modifying saved UTF16 ranges`() {
        val prefix = "اقرأ 😀 "
        val first = reference(prefix.length)
        val second = reference(first.end + 1).copy(label = "اسم عربي")
        val raw = "$prefix$mention $mention"
        val saved = SavedComposer(raw, listOf(first, second))
        val encoded = com.labteto.dshmobile.core.wire.WireJson.encodeToString(SavedComposer.serializer(), saved)
        val shown = ReferenceTransformation(saved.references).filter(AnnotatedString(saved.text))
        assertEquals("$prefix\u2066@Short title\u2069 \u2066@اسم عربي\u2069", shown.text.text)
        assertEquals(raw, saved.text)
        assertEquals(encoded, com.labteto.dshmobile.core.wire.WireJson.encodeToString(SavedComposer.serializer(), saved))
        assertFalse(saved.text.any { it == '\u2066' || it == '\u2069' })
        for (ref in saved.references) {
            assertEquals(mention, saved.text.substring(ref.start, ref.end))
            assertEquals(ref.start, shown.offsetMapping.transformedToOriginal(shown.offsetMapping.originalToTransformed(ref.start)))
            assertEquals(ref.end, shown.offsetMapping.transformedToOriginal(shown.offsetMapping.originalToTransformed(ref.end)))
        }
    }

    @Test fun `folder labels retain a single slash and invalid ranges are ignored`() {
        val ref = DraftReference(0, 5, "@src/", "src/", "folder", "src")
        assertEquals("\u2066@src/\u2069", referenceDisplayLabel(ref))
        assertEquals("text", ReferenceTransformation(listOf(ref)).filter(AnnotatedString("text")).text.text)
    }
}
