package com.labteto.dshmobile.ui.voice

/** A draft after a recognised phrase went into it, and where the caret belongs now. */
data class DictationInsertion(val text: String, val cursor: Int)

/**
 * Puts a recognised phrase into [draft] at [cursor], as ordinary text.
 *
 * Nothing in the draft is removed or rewritten: the phrase lands between the two characters the
 * caret sits between, and the caret ends up after it. The only thing added besides the phrase is a
 * space on a side that needs one, so dictating twice does not glue "hello" and "world" together
 * and dictating into the middle of a sentence does not leave a double space.
 *
 * Whether a side needs a space is [needsSeparator]'s call, and it is script aware: Chinese,
 * Japanese and Thai are written without spaces between words, so two takes in those scripts join
 * as they would if typed, and a Latin word next to them is not pushed away either.
 *
 * @param cursor the caret offset; clamped into the draft and moved off the middle of a surrogate pair.
 * @return the new draft and caret, or null when [spoken] holds nothing but whitespace.
 */
fun insertDictation(draft: String, cursor: Int, spoken: String): DictationInsertion? {
    val phrase = spoken.trim { it.isWhitespace() || Character.isSpaceChar(it) }
    if (phrase.isEmpty()) return null
    val at = safeCursor(draft, cursor)
    val before = if (at > 0) Character.codePointBefore(draft, at) else null
    val after = if (at < draft.length) Character.codePointAt(draft, at) else null
    val lead = if (before != null && needsSeparator(before, phrase.codePointAt(0))) " " else ""
    val trail = if (after != null && needsSeparator(phrase.codePointBefore(phrase.length), after)) " " else ""
    val text = draft.substring(0, at) + lead + phrase + trail + draft.substring(at)
    return DictationInsertion(text, at + lead.length + phrase.length)
}

/** What [insertDictation] would add to the draft, and where: the phrase with the spaces it brings. */
data class DictationChunk(val at: Int, val text: String)

/**
 * The text [insertDictation] would put into [draft], for showing it in place before it is final.
 * The preview and the final insertion agree because the preview is computed from the insertion.
 */
fun dictationChunk(draft: String, cursor: Int, spoken: String): DictationChunk? {
    val inserted = insertDictation(draft, cursor, spoken) ?: return null
    val at = safeCursor(draft, cursor)
    return DictationChunk(at, inserted.text.substring(at, at + inserted.text.length - draft.length))
}

/** The caret clamped into the draft and never between the halves of a surrogate pair. */
internal fun safeCursor(draft: String, cursor: Int): Int {
    val at = cursor.coerceIn(0, draft.length)
    return if (at in 1 until draft.length && Character.isHighSurrogate(draft[at - 1]) && Character.isLowSurrogate(draft[at])) at - 1 else at
}

/**
 * Whether a space belongs between [left] and [right] when two runs of text are joined there.
 *
 * No, when either side is already whitespace, when either is from a script written without spaces,
 * when the left one opens something (a bracket, a quote, an `@` or `/` that starts a name) or when
 * the right one closes or ends something (a bracket, a comma, a full stop). Yes otherwise.
 */
internal fun needsSeparator(left: Int, right: Int): Boolean {
    if (isBlank(left) || isBlank(right)) return false
    if (isSpacelessScript(left) || isSpacelessScript(right)) return false
    if (left in OPENING) return false
    if (right in CLOSING) return false
    return true
}

private fun isBlank(codePoint: Int) = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

/**
 * Scripts whose words are not separated by spaces: Han, Kana, Bopomofo, Thai, Lao, Khmer and
 * Myanmar, plus the CJK and full-width punctuation blocks (those code points are "common" to the
 * script lookup, so the block is what recognises them). Hangul is deliberately absent: Korean
 * writes spaces between words.
 */
internal fun isSpacelessScript(codePoint: Int): Boolean {
    val block = Character.UnicodeBlock.of(codePoint)
    if (block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    ) return true
    return when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.BOPOMOFO,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
        -> true
        else -> false
    }
}

private val OPENING: Set<Int> = "([{<«“‘¿¡@#/".map { it.code }.toSet()
private val CLOSING: Set<Int> = ")]}>»”’.,;:!?…%،؛؟।॥".map { it.code }.toSet()

/**
 * One partial hypothesis as the recogniser reports it: the text it has settled on and, separately,
 * the tail it may still revise.
 *
 * Services differ. Some put the whole running text in the stable list and send no unstable one;
 * some split it in two; a few repeat the whole text in both. The result is the running text with
 * nothing doubled: whichever side already contains the other wins, otherwise the two are joined
 * with the separator rule insertion uses.
 */
fun mergePartial(stable: String?, unstable: String?): String {
    val settled = stable.orEmpty().trim()
    val tail = unstable.orEmpty().trim()
    return when {
        tail.isEmpty() -> settled
        settled.isEmpty() -> tail
        tail.startsWith(settled) -> tail
        settled.startsWith(tail) -> settled
        needsSeparator(settled.codePointBefore(settled.length), tail.codePointAt(0)) -> "$settled $tail"
        else -> settled + tail
    }
}

/**
 * The text a take ends with: the recogniser's own final result when it gave one, and otherwise the
 * last partial it showed. A take that was heard and shown on screen is not thrown away because the
 * service came back empty-handed at the very end.
 */
fun chooseFinalText(finalResults: List<String>?, lastPartial: String): String {
    val final = finalResults?.firstOrNull { it.isNotBlank() }?.trim()
    return final ?: lastPartial.trim()
}

/**
 * Turns the recogniser's level reading into 0..1 for the listening indicator.
 *
 * `onRmsChanged` reports decibels, roughly -2 for silence up to 10 for loud speech; services stray
 * a little outside that. The reading is clamped, so a spike never draws outside the indicator.
 */
fun normalizeRms(rmsDb: Float): Float {
    if (rmsDb.isNaN()) return 0f
    return ((rmsDb - RMS_FLOOR) / (RMS_CEILING - RMS_FLOOR)).coerceIn(0f, 1f)
}

/** A 0..1 level rounded to the nearest of [LEVEL_STEPS] steps. */
fun quantizeLevel(level: Float): Float = Math.round(level.coerceIn(0f, 1f) * LEVEL_STEPS) / LEVEL_STEPS.toFloat()

private const val LEVEL_STEPS = 8
private const val RMS_FLOOR = -2f
private const val RMS_CEILING = 10f
