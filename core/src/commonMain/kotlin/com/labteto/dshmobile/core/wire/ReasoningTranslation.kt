package com.labteto.dshmobile.core.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer

@Serializable
data class TranslationPreferences(val provider: String, val targetLanguage: String)
@Serializable
data class TranslationLimits(val maxTextChars: Int, val preferences: TranslationPreferences, val availableProviders: List<String>)

suspend fun DshApiClient.translationLimits(): RpcResult<TranslationLimits> =
    call("cotTranslation/limits", JsonObject(emptyMap()), serializer())

suspend fun DshApiClient.translateReasoning(sessionId: String, text: String, targetLanguage: String, provider: String): RpcResult<String> =
    call("cotTranslation/translate", buildJsonObject {
        put("request", buildJsonObject {
            put("sessionId", sessionId); put("text", text)
            put("targetLanguage", targetLanguage); put("provider", provider)
        })
    }, serializer())

/**
 * What the reader sees for a translation provider id, such as the button's "Translate with DeepSeek".
 *
 * `cotTranslation/limits` names a provider by its route key and nothing else, and the host sends no
 * display names for it. The keys are a closed set the host defines, and its own web client keeps a
 * label for each, so this does the same. They are brand names, so they are not localised. A key this
 * build has never seen is made readable (`my-provider` becomes "My Provider") rather than printed raw.
 */
fun translationProviderLabel(provider: String): String =
    KNOWN_TRANSLATION_PROVIDERS[provider] ?: humanisedProviderId(provider)

private val KNOWN_TRANSLATION_PROVIDERS = mapOf(
    "bing" to "Bing",
    "google" to "Google",
    // Both paid routes go to DeepSeek; the second is billed to an API key rather than the account.
    "deepseek-account" to "DeepSeek",
    "deepseek-official" to "DeepSeek API",
)

private val BRAND_WORDS = mapOf("deepseek" to "DeepSeek", "openai" to "OpenAI", "api" to "API")

private fun humanisedProviderId(provider: String): String = provider
    .split('-', '_', ' ')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { word -> BRAND_WORDS[word.lowercase()] ?: word.replaceFirstChar { it.uppercaseChar() } }

/** Request caps count UTF-16 code units; never split a surrogate pair. */
fun translationFragments(text: String, maxChars: Int): List<String> {
    require(maxChars >= 2)
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + maxChars).coerceAtMost(text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        result += text.substring(start, end)
        start = end
    }
    return result
}
