package com.labteto.dshmobile.core.browser

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ToolResultNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Local web addresses a session mentioned, newest first and each once: what the agent wrote and
 * what its tools printed (a dev server's "Local: http://localhost:5173/"). The Browser tab offers
 * them; it never opens one by itself.
 *
 * Only the last [scanChars] characters of each message or result are read, so a huge log costs a
 * bounded scan.
 */
fun mentionedLocalAddresses(nodes: List<ChatNode>, limit: Int = 4, scanChars: Int = 32 * 1024): List<String> {
    val found = LinkedHashMap<String, Unit>()
    for (node in nodes.asReversed()) {
        val text = when (node) {
            is AssistantMessageNode -> node.plainText
            is ToolResultNode -> node.content?.let { collectStrings(it, scanChars) }
            else -> null
        } ?: continue
        val window = if (text.length > scanChars) text.substring(text.length - scanChars) else text
        // Newest first within one text too: the last address a dev server printed is the live one.
        for (link in findLocalWebLinks(window).asReversed()) {
            val url = (parseBrowserAddress(link.url) as? AddressInput.Web)?.address?.url ?: continue
            if (url !in found) found[url] = Unit
            if (found.size >= limit) return found.keys.toList()
        }
    }
    return found.keys.toList()
}

/** Every string in a tool result body, joined by line breaks, up to about [budget] characters. */
private fun collectStrings(element: JsonElement, budget: Int): String {
    val out = StringBuilder()
    fun walk(e: JsonElement) {
        if (out.length > budget * 2) return
        when (e) {
            is JsonPrimitive -> if (e.isString) out.append(e.content).append('\n')
            is JsonArray -> e.forEach { walk(it) }
            is JsonObject -> e.values.forEach { walk(it) }
        }
    }
    walk(element)
    return out.toString()
}
