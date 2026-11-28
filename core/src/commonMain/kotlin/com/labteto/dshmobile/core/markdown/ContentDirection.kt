package com.labteto.dshmobile.core.markdown

/**
 * Whether [text] reads right to left, judged by its first strong character the way a browser's
 * `dir="auto"` does: true for Hebrew, Arabic and the other right-to-left scripts, false for any
 * other letter, null when the text has no letter at all (digits, punctuation, emoji only).
 *
 * Inline `code` spans are skipped: an Arabic sentence that opens with `npm test` is still Arabic.
 * Digits are weak on purpose, Arabic-Indic ones included, so "٣ ملفات" and "3 files" each follow
 * their words.
 */
fun firstStrongIsRtl(text: String): Boolean? {
    var inCode = false
    for (c in text) {
        if (c == '`') {
            inCode = !inCode
            continue
        }
        if (inCode || !c.isLetter()) continue
        return isRtlLetter(c)
    }
    return null
}

private fun isRtlLetter(c: Char): Boolean {
    val code = c.code
    return code in 0x0590..0x08FF || // Hebrew, Arabic, Syriac, Thaana, NKo, Samaritan, Mandaic, Arabic Extended
        code in 0xFB1D..0xFDFF || // Hebrew and Arabic presentation forms A
        code in 0xFE70..0xFEFF // Arabic presentation forms B
}
