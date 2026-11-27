package com.labteto.dshmobile.ui.screens.main

/**
 * [text], a command name such as `/compact`, made safe to put inside a sentence in the reader's
 * language: in a right-to-left language the slash would otherwise drift to the wrong end of it.
 *
 * Android uses `BidiFormatter.getInstance().unicodeWrap`, as the system line always did, which leaves
 * the text alone in a left-to-right language and embeds it with marks around it in a right-to-left one.
 * iOS writes the same characters for the left-to-right text a command name is.
 */
internal expect fun isolatedCommand(text: String): String
