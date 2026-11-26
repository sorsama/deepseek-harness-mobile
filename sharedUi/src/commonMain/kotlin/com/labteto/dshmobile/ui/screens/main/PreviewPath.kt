package com.labteto.dshmobile.ui.screens.main

/** A host file path is never interpreted as an Android path or an arbitrary external scheme. */
fun previewPath(value: String): String? {
    val raw = value.trim().removeSurrounding("<", ">")
    if (raw.isEmpty() || raw.startsWith('#')) return null
    if (raw.startsWith("file://")) return fileUriPath(raw)
    if (Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(raw)) return raw
    if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)) return null
    return raw.substringBefore('#')
}

/** The decoded path of a `file://` URI, or null when it is not one the platform can read. */
internal expect fun fileUriPath(uri: String): String?
