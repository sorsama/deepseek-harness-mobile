package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import android.content.SharedPreferences
import com.labteto.dshmobile.connection.DELIVERY_QUEUE
import com.labteto.dshmobile.connection.normalizeDeliveryMode
import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.serialization.Serializable

internal data class ComposerKey(val host: String, val sessionId: String)

/** Owned by SessionStore, so a gallery callback and an upload never follow UI navigation. */
@Serializable
internal data class DraftReference(val start: Int, val end: Int, val mention: String, val label: String, val kind: String, val target: String)

@Serializable
internal data class SavedComposer(val text: String = "", val references: List<DraftReference> = emptyList())

/** Offsets use Kotlin/Compose UTF-16 indices, just like the harness editor. */
internal fun adjustReferences(old: String, next: String, references: List<DraftReference>): List<DraftReference> {
    val prefix = old.commonPrefixWith(next).length
    val suffix = old.drop(prefix).commonSuffixWith(next.drop(prefix)).length
    val oldEnd = old.length - suffix
    val delta = next.length - old.length
    return references.mapNotNull { ref -> when {
        ref.end <= prefix -> ref
        ref.start >= oldEnd -> ref.copy(start = ref.start + delta, end = ref.end + delta)
        else -> null
    } }.filter { it.start >= 0 && it.end <= next.length && next.substring(it.start, it.end) == it.mention && (it.start == 0 || next[it.start - 1].isWhitespace()) && (it.end == next.length || next[it.end].isWhitespace()) }
}

internal class ComposerDraft(val key: ComposerKey, saved: SavedComposer = SavedComposer(), private val save: (SavedComposer) -> Unit = {}) {
    private var value by mutableStateOf(saved.text)
    var references by mutableStateOf(saved.references.filter { it.start >= 0 && it.end <= saved.text.length && it.end >= it.start && saved.text.substring(it.start, it.end) == it.mention })
        private set
    var selection by mutableIntStateOf(saved.text.length)
    private var cleared: SavedComposer? = null
    var text: String
        get() = value
        set(next) {
            if (next.isEmpty() && value.isNotEmpty()) cleared = SavedComposer(value, references)
            references = if (value.isEmpty() && next == cleared?.text) cleared!!.references else adjustReferences(value, next, references)
            value = next; selection = selection.coerceAtMost(next.length); persist()
        }
    private fun persist() = save(SavedComposer(value, references))
    fun insertReference(start: Int, end: Int, mention: String, label: String, kind: String, target: String) {
        require(start in 0..text.length && end in start..text.length)
        val separator = if (start > 0 && !text[start - 1].isWhitespace()) " " else ""
        val referenceStart = start + separator.length
        text = text.replaceRange(start, end, "$separator$mention ")
        references = (references + DraftReference(referenceStart, referenceStart + mention.length, mention, label, kind, target)).sortedBy { it.start }
        selection = referenceStart + mention.length + 1
        persist()
    }
    var mode by mutableStateOf(DELIVERY_QUEUE)
        private set
    private var modeManuallySelected by mutableStateOf(false)
    var preparing by mutableStateOf(false)
    var submitting by mutableStateOf(false)
    val attachments = mutableStateListOf<PendingAttachment>()

    /** Apply the app default until this composer has an explicit per-session choice. */
    fun applyDefaultMode(defaultMode: String) {
        if (!modeManuallySelected) mode = normalizeDeliveryMode(defaultMode)
    }

    fun selectMode(selectedMode: String) {
        mode = normalizeDeliveryMode(selectedMode)
        modeManuallySelected = true
    }

    fun restoreRejected(submittedText: String, submitted: List<PendingAttachment>) {
        if (text.isBlank()) text = submittedText
        attachments.addAll(0, submitted.filterNot { it in attachments })
    }
}

internal class ComposerRepository(private val preferences: SharedPreferences? = null) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val drafts = mutableMapOf<ComposerKey, ComposerDraft>()
    fun get(key: ComposerKey): ComposerDraft = drafts.getOrPut(key) {
        val storageKey = "${key.host}/${key.sessionId}"
        val raw = preferences?.getString(storageKey, null)
        val saved = raw?.let { runCatching { WireJson.decodeFromString(SavedComposer.serializer(), it) }.getOrElse { _ -> SavedComposer(it) } } ?: SavedComposer()
        ComposerDraft(key, saved) { preferences?.edit()?.putString(storageKey, WireJson.encodeToString(SavedComposer.serializer(), it))?.apply() }
    }
    var imagePickTarget: Pair<ComposerDraft, com.labteto.dshmobile.core.wire.dto.ImageLimitsView>? = null
    var filePickTarget: ComposerDraft? = null
}
