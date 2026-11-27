package com.labteto.dshmobile.ui.screens.main

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Only presentation changes: saved drafts and prompt mentions retain their existing wire form. */
internal class ReferenceTransformation(
    private val references: List<DraftReference>,
    private val tokenStyle: SpanStyle? = null,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val valid = references.sortedBy { it.start }.filter {
            it.start >= 0 && it.end <= text.length && it.end > it.start &&
                text.text.substring(it.start, it.end) == it.mention
        }
        val spans = mutableListOf<ReferenceDisplaySpan>()
        val display = buildString {
            var cursor = 0
            valid.forEach { ref ->
                if (ref.start < cursor) return@forEach
                append(text.text.substring(cursor, ref.start))
                val start = length
                append(referenceDisplayLabel(ref))
                spans += ReferenceDisplaySpan(ref.start, ref.end, start, length)
                cursor = ref.end
            }
            append(text.text.substring(cursor))
        }
        val annotated = AnnotatedString.Builder(display).apply {
            tokenStyle?.let { style -> spans.forEach { addStyle(style, it.displayStart, it.displayEnd) } }
        }.toAnnotatedString()
        return TransformedText(annotated, object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = mapReferenceOffset(offset, spans, false)
            override fun transformedToOriginal(offset: Int): Int = mapReferenceOffset(offset, spans, true)
        })
    }
}

internal fun referenceDisplayLabel(reference: DraftReference): String =
    technicalDisplay("@${reference.label.replace('\n', ' ').replace('\r', ' ')}${if (reference.kind == "folder" && !reference.label.endsWith('/')) "/" else ""}")

internal data class ReferenceDisplaySpan(val rawStart: Int, val rawEnd: Int, val displayStart: Int, val displayEnd: Int)

/** A tap within a reference selects its nearest edge, never the hidden URI's interior. */
internal fun mapReferenceOffset(offset: Int, spans: List<ReferenceDisplaySpan>, reverse: Boolean): Int {
    var delta = 0
    spans.forEach { span ->
        val start = if (reverse) span.displayStart else span.rawStart
        val end = if (reverse) span.displayEnd else span.rawEnd
        val targetStart = if (reverse) span.rawStart else span.displayStart
        val targetEnd = if (reverse) span.rawEnd else span.displayEnd
        if (offset < start) return offset + delta
        if (offset <= end) return if (offset - start < (end - start + 1) / 2) targetStart else targetEnd
        delta = targetEnd - end
    }
    return offset + delta
}

/** Backspace, forward-delete and range replacement remove whole references, including via IME. */
internal fun atomicReferenceEdit(old: String, next: TextFieldValue, references: List<DraftReference>): TextFieldValue {
    if (old == next.text) {
        fun snap(offset: Int): Int {
            val ref = references.firstOrNull { offset > it.start && offset < it.end } ?: return offset
            return if (offset - ref.start < (ref.end - ref.start + 1) / 2) ref.start else ref.end
        }
        return next.copy(selection = TextRange(snap(next.selection.start), snap(next.selection.end)))
    }
    val prefix = old.commonPrefixWith(next.text).length
    val suffix = old.drop(prefix).commonSuffixWith(next.text.drop(prefix)).length
    val end = old.length - suffix
    val touched = references.filter { (it.start < end && it.end > prefix) || (prefix == end && prefix > it.start && prefix < it.end) }
    if (touched.isEmpty()) return separateReferenceBoundaries(old, next, references)
    val start = minOf(prefix, touched.minOf { it.start })
    val expandedEnd = maxOf(end, touched.maxOf { it.end })
    val inserted = next.text.substring(prefix, next.text.length - suffix)
    return separateReferenceBoundaries(old,
        TextFieldValue(old.replaceRange(start, expandedEnd, inserted), TextRange(start + inserted.length)), references)
}

/** Typing immediately beside a token keeps a separator, so edit tracking never exposes its URI. */
private fun separateReferenceBoundaries(old: String, next: TextFieldValue, references: List<DraftReference>): TextFieldValue {
    val prefix = old.commonPrefixWith(next.text).length
    val suffix = old.drop(prefix).commonSuffixWith(next.text.drop(prefix)).length
    val oldEnd = old.length - suffix
    val delta = next.text.length - old.length
    val insertions = sortedSetOf<Int>()
    references.forEach { ref ->
        val start = when {
            ref.end <= prefix -> ref.start
            ref.start >= oldEnd -> ref.start + delta
            else -> return@forEach
        }
        val end = start + ref.mention.length
        if (start < 0 || end > next.text.length || next.text.substring(start, end) != ref.mention) return@forEach
        if (start > 0 && !next.text[start - 1].isWhitespace()) insertions += start
        if (end < next.text.length && !next.text[end].isWhitespace()) insertions += end
    }
    if (insertions.isEmpty()) return next
    var text = next.text
    insertions.toList().asReversed().forEach { text = text.substring(0, it) + " " + text.substring(it) }
    fun mapped(offset: Int) = offset + insertions.count { it <= offset }
    return TextFieldValue(text, TextRange(mapped(next.selection.start), mapped(next.selection.end)),
        next.composition?.let { TextRange(mapped(it.start), mapped(it.end)) })
}
