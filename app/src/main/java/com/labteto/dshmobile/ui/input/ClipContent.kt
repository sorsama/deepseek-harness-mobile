package com.labteto.dshmobile.ui.input

import android.content.ClipData
import android.net.Uri
import androidx.compose.ui.platform.ClipboardManager

/** One item of a clipboard or a drag, reduced to the two things the composer looks at. */
data class ClipPart(val uri: String?, val text: String?)

/** What a clip holds once it is sorted: files to attach and text to type. */
data class ClipSplit(val files: List<String>, val text: String)

/**
 * Sorts a clip into files and text.
 *
 * An item with a `content://` address is a file, whatever else it says about itself: a drag from the
 * Files app or a copied image carries a display name as text next to the address, and that name is
 * not something anyone meant to type. Every other item contributes its text, or its address when it
 * has no text (a link dragged out of a browser). `file://` addresses are not accepted as files: the
 * platform refuses to share them, and a grant exists only for `content://`.
 */
fun splitClip(parts: List<ClipPart>): ClipSplit {
    val files = mutableListOf<String>()
    val texts = mutableListOf<String>()
    for (part in parts) {
        val uri = part.uri
        if (uri != null && uri.startsWith("content://")) {
            files += uri
            continue
        }
        val text = part.text?.takeIf { it.isNotEmpty() } ?: uri?.takeIf { it.isNotBlank() }
        if (text != null) texts += text
    }
    return ClipSplit(files.distinct(), texts.joinToString("\n"))
}

/** The items of a platform clip as [ClipPart]s. */
fun ClipData.parts(context: android.content.Context): List<ClipPart> =
    (0 until itemCount).map { index ->
        val item = getItemAt(index)
        ClipPart(uri = item.uri?.toString(), text = item.coerceToTextOrNull(context))
    }

private fun ClipData.Item.coerceToTextOrNull(context: android.content.Context): String? =
    // Text only when the item *is* text: coercing a content address to text would read the file.
    if (uri == null) runCatching { coerceToText(context)?.toString() }.getOrNull() else text?.toString()

/**
 * The files on the clipboard, when it holds files and nothing to type. Empty otherwise, so a paste
 * that has text in it is left to the text field.
 */
fun clipboardFiles(clipboard: ClipboardManager, context: android.content.Context): List<Uri> {
    val data = runCatching { clipboard.getClip()?.clipData }.getOrNull() ?: return emptyList()
    val split = splitClip(data.parts(context))
    return if (split.text.isBlank()) split.files.map(Uri::parse) else emptyList()
}
