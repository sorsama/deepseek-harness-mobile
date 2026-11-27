package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.data.wallClockMillis

/**
 * What a timed question's half-typed answer leaves on disk, and when it goes.
 *
 * A draft is written on every edit so a question survives the app being swapped out mid-answer.
 * Nothing ever took one back out, so free text typed into a question, which can be a secret, sat in
 * the `question-drafts` file for good and the file only grew. A draft now goes when its card is
 * answered, dismissed or settled, and anything left behind by a crash or a card that never came
 * back is swept once it is old enough that no timed question can still be waiting on it.
 */
internal const val QUESTION_DRAFT_FILE = "question-drafts"

/** A draft that no live card owns is swept once it is this old. A timed question lasts minutes. */
internal const val QUESTION_DRAFT_TTL_MS = 24L * 60 * 60 * 1000

private const val STAMP_SUFFIX = "#savedAt"

/** The key a draft is stored under: the host it belongs to, then the card's own key. */
internal fun questionDraftKey(host: String?, cardKey: String): String = "$host/$cardKey"

/** Where a draft's save time is kept, beside the draft itself. */
internal fun questionDraftStampKey(draftKey: String): String = draftKey + STAMP_SUFFIX

internal fun isQuestionDraftStampKey(key: String): Boolean = key.endsWith(STAMP_SUFFIX)

/** One stored draft and when it was last written; null for a draft written before saves were dated. */
internal data class StoredDraft(val key: String, val savedAt: Long?)

/** What a sweep decided: which drafts to delete, and which undated ones to date from now. */
internal data class DraftSweep(val delete: List<String>, val stampNow: List<String>)

/**
 * Decides which stored drafts to drop.
 *
 *  - A draft a live card owns is kept, however old.
 *  - An undated one is kept this time and dated now, so it ages from here instead of being judged
 *    on a save time nobody recorded.
 *  - A dated one no live card owns goes once it is older than [ttlMs].
 */
internal fun sweepQuestionDrafts(
    drafts: List<StoredDraft>,
    liveKeys: Set<String>,
    now: Long,
    ttlMs: Long = QUESTION_DRAFT_TTL_MS,
): DraftSweep {
    val delete = mutableListOf<String>()
    val stamp = mutableListOf<String>()
    for (draft in drafts) {
        if (draft.key in liveKeys) continue
        val savedAt = draft.savedAt
        when {
            savedAt == null -> stamp += draft.key
            now - savedAt > ttlMs -> delete += draft.key
        }
    }
    return DraftSweep(delete, stamp)
}

/**
 * The file the drafts are kept in, as the few calls [QuestionDraftStore] makes on it. Android keeps them in the
 * `question-drafts` `SharedPreferences` file it always used (a draft's JSON as a String, its save time as a Long beside
 * it), so nothing on a phone moves; iOS keeps the same entries in `NSUserDefaults`.
 */
internal interface QuestionDraftFile {
    fun getString(key: String): String?

    fun contains(key: String): Boolean

    /** Every entry: a draft's JSON (a String) or a save time (a Long). */
    val all: Map<String, *>

    /** Apply [changes] together: each key set to its String or Long value, or removed when the value is null. */
    fun apply(changes: Map<String, Any?>)
}

/** The question drafts of this device, opened once per composition that asks. */
@Composable
internal expect fun rememberQuestionDraftStore(): QuestionDraftStore

/** The `question-drafts` preferences, behind the few things the question surface does to them. */
internal class QuestionDraftStore(private val prefs: QuestionDraftFile, private val clock: () -> Long = ::wallClockMillis) {
    fun read(draftKey: String): String? = prefs.getString(draftKey)

    fun save(draftKey: String, json: String) {
        prefs.apply(mapOf(draftKey to json, questionDraftStampKey(draftKey) to clock()))
    }

    /** Remove a draft and its date. Safe when there is none. */
    fun drop(draftKey: String) {
        if (!prefs.contains(draftKey) && !prefs.contains(questionDraftStampKey(draftKey))) return
        prefs.apply(mapOf(draftKey to null, questionDraftStampKey(draftKey) to null))
    }

    /** Apply [sweepQuestionDrafts] to what is stored, given the drafts live cards own. */
    fun sweep(liveKeys: Set<String>) {
        val all = prefs.all
        val drafts = all.keys.filterNot(::isQuestionDraftStampKey).map { key ->
            StoredDraft(key, (all[questionDraftStampKey(key)] as? Long))
        }
        val plan = sweepQuestionDrafts(drafts, liveKeys, clock())
        // Stamp keys with no draft beside them are debris from an earlier crash.
        val strayStamps = all.keys.filter { isQuestionDraftStampKey(it) && it.removeSuffix(STAMP_SUFFIX) !in all }
        if (plan.delete.isEmpty() && plan.stampNow.isEmpty() && strayStamps.isEmpty()) return
        // The three sets name different keys (a stray stamp has no draft, the others do), so one map holds them.
        val changes = LinkedHashMap<String, Any?>()
        plan.delete.forEach { changes[it] = null; changes[questionDraftStampKey(it)] = null }
        plan.stampNow.forEach { changes[questionDraftStampKey(it)] = clock() }
        strayStamps.forEach { changes[it] = null }
        prefs.apply(changes)
    }
}
