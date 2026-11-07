package com.labteto.dshmobile.core.session

/** Resolve host paths without using the Android device's filesystem conventions. */
fun resolvePreviewReference(document: String, reference: String): String {
    val ref = reference.replace('\\', '/').substringBefore('#')
    if (ref.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(ref)) return ref
    val parent = document.replace('\\', '/').substringBeforeLast('/', "")
    val combined = if (parent.isEmpty()) ref else "$parent/$ref"
    val parts = mutableListOf<String>()
    combined.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty() && parts.last() != ".." && !parts.last().endsWith(':')) parts.removeAt(parts.lastIndex) else parts.add(part)
            else -> parts.add(part)
        }
    }
    return (if (combined.startsWith('/')) "/" else "") + parts.joinToString("/")
}

/** Convert a browser-normalized resource URL back to a readRelated relative path. */
fun relativePreviewResource(documentUrlPath: String, resourceUrlPath: String): String {
    val parent = documentUrlPath.substringBeforeLast('/').split('/').filter(String::isNotEmpty)
    val target = resourceUrlPath.split('/').filter(String::isNotEmpty)
    val shared = parent.zip(target).takeWhile { it.first == it.second }.size
    return (List(parent.size - shared) { ".." } + target.drop(shared)).joinToString("/")
}
