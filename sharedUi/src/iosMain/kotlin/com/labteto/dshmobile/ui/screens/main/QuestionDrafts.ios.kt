package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import platform.Foundation.NSUserDefaults

/** The question drafts in `NSUserDefaults`, one entry holding them all. */
@Composable
internal actual fun rememberQuestionDraftStore(): QuestionDraftStore = remember { QuestionDraftStore(UserDefaultsDraftFile()) }

/**
 * [QuestionDraftFile] as one JSON object under [KEY] in `NSUserDefaults`: each draft's JSON as a string and its save
 * time as a number beside it, the same entries Android keeps in its `question-drafts` file. One object, so a change of
 * several keys is written together, as Android's one editor writes them. A `-DshReset` launch forgets it with the rest of
 * the app's defaults.
 */
private class UserDefaultsDraftFile(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : QuestionDraftFile {
    override fun getString(key: String): String? = (read()[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    override fun contains(key: String): Boolean = key in read()

    override val all: Map<String, *>
        get() = read().mapValues { (_, value) ->
            val primitive = value as? JsonPrimitive
            when {
                primitive == null -> null
                primitive.isString -> primitive.content
                else -> primitive.longOrNull
            }
        }

    override fun apply(changes: Map<String, Any?>) {
        val next = LinkedHashMap(read())
        changes.forEach { (key, value) ->
            when (value) {
                null -> next.remove(key)
                is String -> next[key] = JsonPrimitive(value)
                is Long -> next[key] = JsonPrimitive(value)
                else -> error("a question draft entry is a String or a Long, not ${value::class}")
            }
        }
        defaults.setObject(WireJson.encodeToString(JsonObject.serializer(), JsonObject(next)), forKey = KEY)
    }

    private fun read(): JsonObject {
        val raw = defaults.stringForKey(KEY) ?: return JsonObject(emptyMap())
        return runCatching { WireJson.decodeFromString(JsonObject.serializer(), raw) }.getOrDefault(JsonObject(emptyMap()))
    }

    private companion object {
        const val KEY = "dsh.questionDrafts"
    }
}
