package com.labteto.dshmobile.ui.screens.main

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageDirectionRightToLeft
import platform.Foundation.characterDirectionForLanguage
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

private val RLM = Char(0x200F)
private val LRE = Char(0x202A)
private val PDF = Char(0x202C)

/**
 * What Android's `BidiFormatter.unicodeWrap` writes for left-to-right text, which a command name is: nothing
 * in a left-to-right language, and in a right-to-left one a right-to-left mark, the text embedded left to
 * right, and another mark.
 */
internal actual fun isolatedCommand(text: String): String {
    val rtl = NSLocale.characterDirectionForLanguage(NSLocale.currentLocale.languageCode) == NSLocaleLanguageDirectionRightToLeft
    return if (rtl) "$RLM$LRE$text$PDF$RLM" else text
}
