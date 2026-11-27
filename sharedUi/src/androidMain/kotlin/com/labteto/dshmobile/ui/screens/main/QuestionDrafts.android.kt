package com.labteto.dshmobile.ui.screens.main

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The `question-drafts` preferences file, as the question surface has always opened it. */
@Composable
internal actual fun rememberQuestionDraftStore(): QuestionDraftStore {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        QuestionDraftStore(SharedPreferencesDraftFile(appContext.getSharedPreferences(QUESTION_DRAFT_FILE, Context.MODE_PRIVATE)))
    }
}

/** [QuestionDraftFile] over the `SharedPreferences` calls the store made before it was shared: one editor per change. */
private class SharedPreferencesDraftFile(private val prefs: SharedPreferences) : QuestionDraftFile {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun contains(key: String): Boolean = prefs.contains(key)

    override val all: Map<String, *> get() = prefs.all

    override fun apply(changes: Map<String, Any?>) {
        val edit = prefs.edit()
        changes.forEach { (key, value) ->
            when (value) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Long -> edit.putLong(key, value)
                else -> error("a question draft entry is a String or a Long, not ${value::class}")
            }
        }
        edit.apply()
    }
}
