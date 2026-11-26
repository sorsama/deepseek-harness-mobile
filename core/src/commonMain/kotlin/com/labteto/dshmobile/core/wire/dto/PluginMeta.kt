package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The display fields the harness reads from a plugin package without running it
 * (`PluginLocalizedMeta`). Only the title is read here; the description and the icon are ignored.
 *
 * [title] is plain text, or an object of translations indexed by lowercase language id with an
 * English fallback (`{"en": "Relay", "zh-cn": "..."}`).
 */
@Serializable
data class PluginMeta(val title: JsonElement? = null)

/**
 * The title for the first of [languageTags] the plugin translates (most specific first, such as
 * `zh-cn` then `zh`), else its English one, else any translation it has. Null when there is no
 * usable title, so the caller can fall back to the technical name.
 */
fun PluginMeta.titleFor(languageTags: List<String>): String? {
    val title = title ?: return null
    if (title is JsonPrimitive) return title.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    val translations = title as? JsonObject ?: return null
    fun text(key: String): String? =
        (translations[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    languageTags.map { it.lowercase().replace('_', '-') }.firstNotNullOfOrNull { text(it) }?.let { return it }
    text("en")?.let { return it }
    return translations.keys.firstNotNullOfOrNull { text(it) }
}
