package com.labteto.dshmobile.ui.components

import com.labteto.dshmobile.ui.screens.main.technicalDisplay

/**
 * Whether [text] has a character that is written right to left: Hebrew, Arabic (and Urdu, which is
 * written in it), Syriac, Thaana and N'Ko, and the Hebrew and Arabic presentation forms. Only the
 * letters of the basic plane; that is every language the app ships and then some.
 */
internal fun hasRightToLeftText(text: String): Boolean {
    for (ch in text) {
        val c = ch.code
        if (c in 0x0590..0x08FF || c in 0xFB1D..0xFDFF || c in 0xFE70..0xFEFF) return true
    }
    return false
}

/**
 * An inline code span as it is drawn inside [paragraph]: isolated left to right when the paragraph is
 * written right to left in any part, so the span keeps its own order and its path or command is not
 * split by the prose around it. Left alone otherwise, so a message in English (or any left-to-right
 * language) is copied with exactly the characters it was written with.
 */
internal fun inlineCodeDisplay(code: String, paragraph: String): String =
    if (hasRightToLeftText(paragraph)) technicalDisplay(code) else code
