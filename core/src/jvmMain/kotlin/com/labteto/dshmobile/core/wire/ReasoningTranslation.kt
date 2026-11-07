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
