package com.labteto.dshmobile.translation

import java.lang.Character.UnicodeScript

/**
 * Whether [text] is, as far as a cheap check can tell, already written in [targetTag]'s language, so
 * automatic translation can skip it and spend no model call.
 *
 * The check only ever answers true when it is very sure, because a wrong "yes" costs the reader an
 * automatic translation (the manual button is still there) and a wrong "no" costs one wasted call.
 * It has no dependency and reads at most [SAMPLE_CHARS] characters:
 *
 *  - For a target with a script of its own (Chinese, Japanese, Korean, Arabic, Hebrew, Cyrillic,
 *    Devanagari, Bengali, Thai, Greek), the text is already in it when most of its letters are in
 *    that script. Scripts shared by several languages (Arabic by Urdu and Persian, Cyrillic by
 *    Russian and Ukrainian) are treated as one: telling those apart is language detection.
 *  - English is told by its function words ("the", "and", "that"...): a plain share of them. No
 *    other Latin-script language is checked, because Spanish, French and Portuguese cannot be told
 *    apart from one another, or from English code and identifiers, without a real detector.
 *    A Latin target other than English is never skipped.
 *  - Short text is never skipped: there is too little to judge.
 */
fun looksAlreadyInTarget(text: String, targetTag: String): Boolean {
    val language = targetTag.substringBefore('-').substringBefore('_').lowercase()
    val sample = if (text.length > SAMPLE_CHARS) text.substring(0, SAMPLE_CHARS) else text
    val counts = ScriptCounts.of(sample)
    if (counts.letters < MIN_LETTERS) return false
    return when (language) {
        "zh" -> counts.share(UnicodeScript.HAN) >= SCRIPT_SHARE && counts.share(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA) < 0.02
        "ja" -> counts.share(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA) >= 0.05 &&
            counts.share(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HAN) >= SCRIPT_SHARE
        "ko" -> counts.share(UnicodeScript.HANGUL) >= SCRIPT_SHARE
        "ar", "fa", "ur", "ps" -> counts.share(UnicodeScript.ARABIC) >= SCRIPT_SHARE
        "he" -> counts.share(UnicodeScript.HEBREW) >= SCRIPT_SHARE
        "ru", "uk", "bg", "be", "sr", "mk", "kk" -> counts.share(UnicodeScript.CYRILLIC) >= SCRIPT_SHARE
        "hi", "mr", "ne" -> counts.share(UnicodeScript.DEVANAGARI) >= SCRIPT_SHARE
        "bn" -> counts.share(UnicodeScript.BENGALI) >= SCRIPT_SHARE
        "th" -> counts.share(UnicodeScript.THAI) >= SCRIPT_SHARE
        "el" -> counts.share(UnicodeScript.GREEK) >= SCRIPT_SHARE
        "en" -> looksEnglish(sample, counts)
        else -> false
    }
}

private const val SAMPLE_CHARS = 2_000
private const val MIN_LETTERS = 12
private const val SCRIPT_SHARE = 0.5

/** Words that are English and not Spanish, French or Portuguese, so a share of them is meaningful. */
private val ENGLISH_WORDS = setOf(
    "the", "and", "is", "are", "was", "were", "to", "of", "that", "this", "with", "for", "it", "in",
    "not", "be", "have", "has", "will", "i", "we", "you", "can", "should", "need", "let", "then",
    "but", "if", "by", "from", "which", "what", "when", "how", "also", "so", "they", "there",
)

private fun looksEnglish(sample: String, counts: ScriptCounts): Boolean {
    // Mostly Latin letters, or it is some other language with a few English words in it.
    if (counts.share(UnicodeScript.LATIN) < 0.9) return false
    var words = 0
    var english = 0
    var start = -1
    for (i in 0..sample.length) {
        val c = if (i < sample.length) sample[i] else ' '
        if (c in 'A'..'Z' || c in 'a'..'z' || c == '\'') {
            if (start < 0) start = i
        } else if (start >= 0) {
            words++
            if (sample.substring(start, i).lowercase() in ENGLISH_WORDS) english++
            start = -1
        }
    }
    return words >= 10 && english.toDouble() / words >= 0.3
}

private class ScriptCounts(private val byScript: Map<UnicodeScript, Int>, val letters: Int) {
    fun share(vararg scripts: UnicodeScript): Double =
        if (letters == 0) 0.0 else scripts.sumOf { byScript[it] ?: 0 }.toDouble() / letters

    companion object {
        fun of(text: String): ScriptCounts {
            val counts = HashMap<UnicodeScript, Int>()
            var letters = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (!Character.isLetter(cp)) continue
                letters++
                val script = try { UnicodeScript.of(cp) } catch (_: IllegalArgumentException) { continue }
                counts[script] = (counts[script] ?: 0) + 1
            }
            return ScriptCounts(counts, letters)
        }
    }
}
