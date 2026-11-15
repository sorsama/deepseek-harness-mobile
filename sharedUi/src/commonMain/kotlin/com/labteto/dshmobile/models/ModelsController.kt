package com.labteto.dshmobile.models

import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.LlmModelDiscoveryRequest
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How the screen's list stands. */
sealed interface ModelsPhase {
    data object Loading : ModelsPhase
    data object Ready : ModelsPhase

    /** The harness does not offer the provider calls to this app. */
    data object Unsupported : ModelsPhase

    /** Loading failed; [message] is the harness's own words. */
    data class Failed(val message: String) : ModelsPhase
}

/** What the open editor is doing. */
enum class EditorWork { Saving, Removing, Checking }

/** The last thing the open editor finished. */
sealed interface EditorResult {
    /** The endpoint answered the draft and lists [count] models. */
    data class Checked(val count: Int) : EditorResult

    /** The harness refused; [message] is its own diagnostic, with any typed key masked out. */
    data class Failed(val message: String) : EditorResult

    /** Someone changed these settings since the screen read them. */
    data object Conflict : EditorResult
}

/** What a send waits on while the person decides about a plain-HTTP link. */
enum class PendingSend { Save, Check }

/**
 * The one provider being edited. It never holds the key: the field's text lives only in the
 * screen's composition, and each action is handed it for that one call.
 */
data class EditorState(
    val provider: String,
    /** Adopting a provider from "Add a provider" rather than editing one already set up. */
    val adding: Boolean,
    val baseUrlDraft: String,
    val work: EditorWork? = null,
    val result: EditorResult? = null,
    /** Set while a key is waiting for the plain-HTTP question to be answered. */
    val askCleartext: PendingSend? = null,
)

/** A screen-level announcement once an editor closes on success. */
sealed interface ModelsNotice {
    val displayName: String

    data class Saved(override val displayName: String, val restartNeeded: Boolean) : ModelsNotice
    data class KeyRemoved(override val displayName: String) : ModelsNotice
    data class ProviderRemoved(override val displayName: String) : ModelsNotice
}

data class ModelsState(
    val phase: ModelsPhase = ModelsPhase.Loading,
    val directory: ProviderDirectory? = null,
    val editor: EditorState? = null,
    val notice: ModelsNotice? = null,
    /** A key sent from here crosses a network in the clear. */
    val cleartext: Boolean = false,
) {
    val editing: ProviderRow? get() = editor?.let { e -> directory?.rows?.firstOrNull { it.provider == e.provider } }
}

/**
 * The Models screen's logic: read the directory, edit one provider at a time, and write through
 * the harness the way its own page does (`ProviderEditor.applyOnce`): profile fields as minimal
 * `settings/mutate` path ops fenced on the namespace revision, then the key through
 * `credentials/set`, then a fresh read.
 *
 * The key is never kept. It arrives as an argument, goes into exactly one call, and is masked out
 * of every message this class publishes or logs.
 */
class ModelsController(
    private val port: ModelsPort,
    private val scope: CoroutineScope,
    /** Whether a key sent from here crosses a network in the clear ([keyTravelsInClear]). */
    cleartext: Boolean,
    /** Diagnostics sink; it only ever sees provider ids, error codes and masked messages. */
    private val log: (String) -> Unit = {},
    /** Called after any write lands, so hints elsewhere can re-read readiness. */
    private val onChanged: () -> Unit = {},
) {
    private val _state = MutableStateFlow(ModelsState(cleartext = cleartext))
    val state: StateFlow<ModelsState> = _state.asStateFlow()

    /** The plain-HTTP question is asked once per screen; answering yes covers the rest of the visit. */
    private var cleartextAccepted = false
    private var loading: Job? = null

    fun load() {
        loading?.cancel()
        loading = scope.launch { reload() }
    }

    private suspend fun reload() {
        if (_state.value.directory == null) _state.update { it.copy(phase = ModelsPhase.Loading) }
        when (val load = loadProviderDirectory(port)) {
            is DirectoryLoad.Loaded -> _state.update { it.copy(phase = ModelsPhase.Ready, directory = load.directory) }
            DirectoryLoad.Unsupported -> _state.update { it.copy(phase = ModelsPhase.Unsupported) }
            is DirectoryLoad.Failed -> {
                log("models: load failed")
                // A failed refresh keeps the rows already shown; only a first load shows the failure.
                _state.update { if (it.directory == null) it.copy(phase = ModelsPhase.Failed(load.message)) else it }
            }
        }
    }

    fun open(provider: String) {
        val row = _state.value.directory?.rows?.firstOrNull { it.provider == provider } ?: return
        if (_state.value.editor?.work != null) return
        _state.update {
            it.copy(
                editor = EditorState(provider = provider, adding = row.dormant, baseUrlDraft = row.baseUrl.orEmpty()),
                notice = null,
            )
        }
    }

    fun close() {
        if (_state.value.editor?.work != null) return
        _state.update { it.copy(editor = null) }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun setBaseUrl(text: String) = _state.update { s -> s.copy(editor = s.editor?.copy(baseUrlDraft = text, result = null)) }

    /** Clears a check result or failure once the person edits again. */
    fun keyEdited() = _state.update { s -> s.copy(editor = s.editor?.copy(result = null)) }

    /** The person answered the plain-HTTP question. */
    fun answerCleartext(send: Boolean, key: String) {
        val pending = _state.value.editor?.askCleartext ?: return
        _state.update { s -> s.copy(editor = s.editor?.copy(askCleartext = null)) }
        if (!send) return
        cleartextAccepted = true
        when (pending) {
            PendingSend.Save -> save(key)
            PendingSend.Check -> check(key)
        }
    }

    /** Whether [save] would do anything: a valid new key, or a changed endpoint. */
    fun canSave(key: String): Boolean {
        val s = _state.value
        val row = s.editing ?: return false
        val editor = s.editor ?: return false
        if (editor.work != null) return false
        if (keyProblemOf(key) != null || !baseUrlLooksValid(editor.baseUrlDraft)) return false
        val typed = key.trim().isNotEmpty()
        if (typed && !row.keyWritable) return false
        val writesProfile = profileOps(row, editor, keyTyped = typed).isNotEmpty()
        // A read-only settings document still takes a key; it cannot take a profile change.
        if (writesProfile && s.directory?.writable == false) return false
        return typed || writesProfile
    }

    /** Whether the harness has a real check for this draft (see [check]). */
    fun canCheck(): Boolean {
        val s = _state.value
        val row = s.editing ?: return false
        val editor = s.editor ?: return false
        return row.family == ProviderFamily.PiAi && row.api != null && editor.baseUrlDraft.trim().isNotEmpty() &&
            baseUrlLooksValid(editor.baseUrlDraft)
    }

    fun save(key: String) {
        val s = _state.value
        val row = s.editing ?: return
        val editor = s.editor ?: return
        if (editor.work != null || !canSave(key)) return
        val value = key.trim()
        if (value.isNotEmpty() && s.cleartext && !cleartextAccepted) {
            _state.update { it.copy(editor = editor.copy(askCleartext = PendingSend.Save)) }
            return
        }
        working(EditorWork.Saving)
        scope.launch {
            val failure = runCatchingWire(value) { write(row, editor, value) }
            if (failure != null) {
                // A profile write may have landed before the key was refused: read again, so a
                // retry is fenced on the revision the harness holds now and not on a stale one.
                reload()
                finish(failure)
                return@launch
            }
            val restart = s.directory?.namespaces?.get(row.settingsNs)?.applies == "restart"
            reload()
            onChanged()
            _state.update { it.copy(editor = null, notice = ModelsNotice.Saved(row.displayName, restart)) }
        }
    }

    /** The writes of one save; null when they all landed, else what the editor shows. */
    private suspend fun write(row: ProviderRow, editor: EditorState, key: String): EditorResult? {
        val ops = profileOps(row, editor, keyTyped = key.isNotEmpty())
        if (ops.isNotEmpty()) {
            val revision = _state.value.directory?.namespaces?.get(row.settingsNs)?.revision
            when (val r = port.mutateSettings(row.settingsNs, ops, revision)) {
                is RpcResult.Ok -> Unit
                is RpcResult.Err -> {
                    log("models: settings write refused (${r.error.code}) for ${row.provider}")
                    return if (r.error.code == "settings/conflict") EditorResult.Conflict
                    else EditorResult.Failed(mask(r.error.message, key))
                }
            }
        }
        if (key.isNotEmpty()) {
            when (val r = port.setCredential(row.keyRef, key)) {
                is RpcResult.Ok -> Unit
                is RpcResult.Err -> {
                    log("models: key write refused (${r.error.code}) for ${row.provider}")
                    return EditorResult.Failed(mask(r.error.message, key))
                }
            }
        }
        return null
    }

    /**
     * Ask the endpoint in the draft what it serves, with the typed key. Only the pi-ai family
     * registers this interrogation, and only for an endpoint it does not already describe does it
     * go over the network; naming no route makes it ask the endpoint rather than answer from its
     * own catalog, so a list here means the endpoint and the key answered.
     */
    fun check(key: String) {
        val s = _state.value
        val row = s.editing ?: return
        val editor = s.editor ?: return
        if (editor.work != null || !canCheck() || keyProblemOf(key) != null) return
        val value = key.trim()
        if (value.isNotEmpty() && s.cleartext && !cleartextAccepted) {
            _state.update { it.copy(editor = editor.copy(askCleartext = PendingSend.Check)) }
            return
        }
        working(EditorWork.Checking)
        scope.launch {
            val failure = runCatchingWire(value) {
                val request = LlmModelDiscoveryRequest(
                    baseURL = editor.baseUrlDraft.trim(),
                    api = row.api,
                    apiKey = value.takeIf { it.isNotEmpty() },
                )
                when (val r = port.discoverModels(row.settingsNs, request)) {
                    is RpcResult.Ok -> EditorResult.Checked(r.value.size)
                    is RpcResult.Err -> {
                        log("models: check refused (${r.error.code}) for ${row.provider}")
                        EditorResult.Failed(mask(r.error.message, value))
                    }
                }
            }
            finish(failure)
        }
    }

    /** Remove the stored key; the profile stays, so the provider asks for a key again. */
    fun removeKey() {
        val row = _state.value.editing ?: return
        if (_state.value.editor?.work != null || !row.keyStored || !row.keyWritable) return
        working(EditorWork.Removing)
        scope.launch {
            val failure = runCatchingWire("") {
                when (val r = port.unsetCredential(row.keyRef)) {
                    is RpcResult.Ok -> null
                    is RpcResult.Err -> EditorResult.Failed(r.error.message).also { log("models: key removal refused (${r.error.code})") }
                }
            }
            if (failure != null) return@launch finish(failure)
            reload()
            onChanged()
            _state.update { it.copy(editor = null, notice = ModelsNotice.KeyRemoved(row.displayName)) }
        }
    }

    /**
     * Remove a provider the person added: its key first, so a failed second step leaves the row
     * visible and the whole thing retryable (`removeProviderProfile`), then the profile.
     */
    fun removeProvider() {
        val row = _state.value.editing ?: return
        if (_state.value.editor?.work != null || !row.removable) return
        working(EditorWork.Removing)
        scope.launch {
            val failure = runCatchingWire("") {
                val managedKey = row.apiKeyEnv == deriveKeyRef(row.provider) && row.keyStored && row.keyWritable
                if (managedKey) {
                    val r = port.unsetCredential(row.keyRef)
                    if (r is RpcResult.Err) return@runCatchingWire EditorResult.Failed(r.error.message)
                }
                when (val r = port.mutateSettings(row.settingsNs, listOf(SettingsPathOpView.Unset(row.settingsPath)), null)) {
                    is RpcResult.Ok -> null
                    is RpcResult.Err -> EditorResult.Failed(r.error.message)
                }
            }
            if (failure != null) return@launch finish(failure)
            reload()
            onChanged()
            _state.update { it.copy(editor = null, notice = ModelsNotice.ProviderRemoved(row.displayName)) }
        }
    }

    private fun working(work: EditorWork) =
        _state.update { s -> s.copy(editor = s.editor?.copy(work = work, result = null, askCleartext = null)) }

    private fun finish(result: EditorResult?) =
        _state.update { s -> s.copy(editor = s.editor?.copy(work = null, result = result)) }

    private suspend fun runCatchingWire(key: String, block: suspend () -> EditorResult?): EditorResult? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("models: call failed (${e::class.simpleName})")
        EditorResult.Failed(mask(e.message ?: e::class.simpleName.orEmpty(), key))
    }
}

/** Never let a key echo back in a message the screen shows or a log line records. */
internal fun mask(message: String, key: String): String =
    if (key.length < 4) message else message.replace(key, "••••")

/**
 * The profile writes one save makes, as path ops on the stored section.
 *
 * - `baseURL`: set when the field changed to an address, unset when it was cleared.
 * - pi-ai: a profile that names no key reference gets the derived one when a key is about to be
 *   stored under it; a provider adopted with no key gets an empty profile, which turns it on with
 *   the provider's own sign-in path (an environment key, a cloud credential chain).
 */
internal fun profileOps(row: ProviderRow, editor: EditorState, keyTyped: Boolean): List<SettingsPathOpView> {
    if (row.family == ProviderFamily.Other) return emptyList()
    val ops = mutableListOf<SettingsPathOpView>()
    val draft = editor.baseUrlDraft.trim()
    val before = row.baseUrl.orEmpty()
    if (draft != before) {
        ops += if (draft.isEmpty()) SettingsPathOpView.Unset(row.settingsPath + "baseURL")
        else SettingsPathOpView.Set(row.settingsPath + "baseURL", JsonPrimitive(draft))
    }
    if (row.family == ProviderFamily.PiAi) {
        if (row.apiKeyEnv == null && keyTyped) {
            ops += SettingsPathOpView.Set(row.settingsPath + "apiKeyEnv", JsonPrimitive(row.keyRef))
        }
        if (!row.configured && ops.isEmpty()) ops += SettingsPathOpView.Set(row.settingsPath, JsonObject(emptyMap()))
    }
    return ops
}
