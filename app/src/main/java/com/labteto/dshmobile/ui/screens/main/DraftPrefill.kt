package com.labteto.dshmobile.ui.screens.main

/**
 * The composer text after a command or skill is picked from the `+` sheet.
 *
 * Picking one used to overwrite the draft with `/name `, so a long message typed first was gone
 * the moment the sheet closed. The pick now keeps every character the reader typed:
 *
 *  - an empty draft just takes the [prefix];
 *  - a draft that already starts with it is left alone, so picking twice does not stack it;
 *  - a lone slash word such as `/re` is a command being typed, not a message, so the pick completes
 *    it instead of sitting beside it;
 *  - anything else goes after the [prefix], which makes it the command's argument.
 */
internal fun prefilledDraft(draft: String, prefix: String): String = when {
    draft.isBlank() -> prefix
    draft.startsWith(prefix) -> draft
    draft.startsWith("/") && draft.none { it.isWhitespace() } -> prefix
    else -> prefix + draft
}
