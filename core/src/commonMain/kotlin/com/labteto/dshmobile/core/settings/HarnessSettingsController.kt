package com.labteto.dshmobile.core.settings

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.CredentialInfo
import com.labteto.dshmobile.core.wire.dto.ModelCatalog
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The harness calls the Harness settings pages make, and nothing else. A port rather than the
 * client so the controller is tested against a fake that records exactly what it is handed.
 */
interface HarnessSettingsPort {
    suspend fun describe(): RpcResult<SettingsDescribeValue>
    suspend fun mutate(ns: String, ops: List<SettingsPathOpView>, expectedRevision: Long?): RpcResult<SettingsNamespaceView>
    suspend fun describeCredentials(refs: List<String>): RpcResult<Map<String, CredentialInfo>>
    suspend fun setCredential(ref: String, value: String): RpcResult<Unit>
    suspend fun unsetCredential(ref: String): RpcResult<Unit>
    suspend fun modelCatalog(): RpcResult<ModelCatalog>
}

/** The port over a live harness client. */
open class DshHarnessSettingsPort(private val api: DshApiClient) : HarnessSettingsPort {
    override suspend fun describe() = api.settingsDescribe()

    override suspend fun mutate(ns: String, ops: List<SettingsPathOpView>, expectedRevision: Long?) =
        api.settingsMutate(ns, ops, expectedRevision?.toInt())

    override suspend fun describeCredentials(refs: List<String>) = api.credentialsDescribe(refs)

    override suspend fun setCredential(ref: String, value: String) = api.credentialsSet(ref, value).unit()

    override suspend fun unsetCredential(ref: String) = api.credentialsUnset(ref).unit()

    override suspend fun modelCatalog() = api.sessionModelCatalog()

    private fun <T> RpcResult<T>.unit(): RpcResult<Unit> = when (this) {
        is RpcResult.Ok -> RpcResult.Ok(Unit)
        is RpcResult.Err -> this
    }
}

/** Where the first read of the area stands. */
sealed interface SettingsPhase {
    data object Loading : SettingsPhase

    data object Ready : SettingsPhase

    /** The harness has no `settings/describe`: an older one. */
    data object Unsupported : SettingsPhase

    data class Failed(val failure: SettingsFailure) : SettingsPhase
}

/**
 * The last write to one field that did not land. [serial] changes with every failure, so a screen
 * that keeps its own draft can tell a new refusal from one it has already shown.
 */
data class FieldFailure(val failure: SettingsFailure, val serial: Long)

/** The model catalog the subagent allowlist is built from, read when that page opens. */
sealed interface CatalogState {
    data object Idle : CatalogState

    data object Loading : CatalogState

    data class Ready(val catalog: ModelCatalog) : CatalogState

    data class Failed(val failure: SettingsFailure) : CatalogState
}

/** One thing worth saying once, then forgetting. */
sealed interface SettingsNotice {
    data object KeySaved : SettingsNotice

    data object KeyRemoved : SettingsNotice

    /** A refresh failed; what is on screen is what was read before. */
    data class RefreshFailed(val failure: SettingsFailure) : SettingsNotice
}

data class HarnessSettingsState(
    val phase: SettingsPhase = SettingsPhase.Loading,
    /** Whether the harness's settings document accepts writes; false disables every settings control. */
    val writable: Boolean = true,
    /** The latest accepted row of each namespace, in the order the harness listed them. */
    val namespaces: Map<String, SettingsNamespaceView> = emptyMap(),
    /**
     * Writes in flight, by field. The value is what the write sets, shown while it crosses the wire
     * so a switch moves when it is touched; null means the field is being reset to its default.
     * A failed write is dropped from here, which is the field going back to what the harness holds.
     */
    val writing: Map<FieldId, JsonElement?> = emptyMap(),
    val failures: Map<FieldId, FieldFailure> = emptyMap(),
    /** Credential state by reference, from `credentials/describe`. */
    val credentials: Map<String, CredentialInfo> = emptyMap(),
    /** References with a key being written or removed. */
    val credentialWork: Set<String> = emptySet(),
    val credentialFailures: Map<String, FieldFailure> = emptyMap(),
    val catalog: CatalogState = CatalogState.Idle,
    val notice: SettingsNotice? = null,
) {
    /** The pages this harness offers. */
    val pages: List<SettingsPage>
        get() = settingsPages(SettingsDescribeValue(writable = writable, hasDocument = true, namespaces = namespaces.values.toList()))
}

/**
 * The state behind the Harness settings pages: read the namespaces once, write one change at a
 * time per namespace, and never show a value the harness did not accept.
 *
 * Every write is a `settings/mutate` of path operations fenced on the namespace revision, exactly
 * what the harness's own settings pages send (`ConfigFormController.mutate`): `set` a field,
 * `unset` it to inherit the deployment's value again. The answer carries the namespace's new row,
 * which replaces the old one, so the screen is always drawn from what the harness holds. A
 * `settings/conflict` (someone else saved first) and a refusal both leave the old row in place;
 * a conflict reads everything again.
 *
 * A key is the one value that never comes back. It goes into exactly one call and is not kept: not
 * in [state], not in a failure message (the harness's words are masked before they are stored).
 */
class HarnessSettingsController(
    private val port: HarnessSettingsPort,
    private val scope: CoroutineScope,
    /** Whether a key sent from here crosses a network unencrypted; the screen asks before it does. */
    val cleartext: Boolean = false,
) {
    private val _state = MutableStateFlow(HarnessSettingsState())
    val state: StateFlow<HarnessSettingsState> = _state.asStateFlow()

    /** One queue per namespace; looked up on the caller's thread, before the write is launched. */
    private val locks = HashMap<String, Mutex>()
    private var loading: Job? = null
    private var catalogJob: Job? = null
    private val serial = atomic(0L)

    private fun lockFor(ns: String): Mutex = locks.getOrPut(ns) { Mutex() }

    /** Read every namespace. The first read shows progress; a later one keeps what is on screen. */
    fun load() {
        loading?.cancel()
        loading = scope.launch { reload() }
    }

    private suspend fun reload() {
        if (_state.value.namespaces.isEmpty()) _state.update { it.copy(phase = SettingsPhase.Loading) }
        when (val result = attempt { port.describe() }) {
            is RpcResult.Ok -> {
                val rows = result.value.namespaces.associateBy { it.ns }
                _state.update { it.copy(phase = SettingsPhase.Ready, writable = result.value.writable, namespaces = rows) }
                refreshCredentials()
                // A page that showed the model list reads it again with everything else: a key saved
                // since (here or on the Models page) is what makes models appear.
                if (_state.value.catalog !is CatalogState.Idle) loadCatalog()
            }
            is RpcResult.Err -> {
                val failure = result.error.toSettingsFailure()
                _state.update { current ->
                    when {
                        failure == SettingsFailure.Unavailable -> current.copy(phase = SettingsPhase.Unsupported)
                        // A refresh that fails keeps the rows already shown; only the first read shows the failure.
                        current.namespaces.isNotEmpty() -> current.copy(notice = SettingsNotice.RefreshFailed(failure))
                        else -> current.copy(phase = SettingsPhase.Failed(failure))
                    }
                }
            }
        }
    }

    /**
     * Write [ops] to [ns] as one revision-fenced mutation. [fields] are the fields the write
     * touches, each with what it will read as while the write is in flight (null: its default).
     * [secret] is a value among [ops] that must not appear in anything this class keeps.
     */
    fun write(ns: String, ops: List<SettingsPathOpView>, fields: Map<FieldId, JsonElement?>, secret: String? = null) {
        if (!_state.value.writable || ops.isEmpty()) return
        val lock = lockFor(ns)
        _state.update { it.copy(writing = it.writing + fields, failures = it.failures - fields.keys) }
        scope.launch {
            lock.withLock {
                val revision = _state.value.namespaces[ns]?.revision
                when (val result = attempt { port.mutate(ns, ops, revision) }) {
                    is RpcResult.Ok -> _state.update {
                        it.copy(namespaces = it.namespaces + (ns to result.value), writing = it.writing - fields.keys)
                    }
                    is RpcResult.Err -> {
                        val failure = result.error.toSettingsFailure().masked(secret)
                        _state.update { it.copy(writing = it.writing - fields.keys, failures = it.failures + failuresFor(fields.keys, failure)) }
                        // The row held here is stale: read it again so the next change is fenced on what the harness holds.
                        if (failure == SettingsFailure.Conflict) reload()
                    }
                }
            }
        }
    }

    /**
     * The harness says a namespace changed (`settings/document-updated`). Our own writes announce
     * themselves too, with a revision this class already holds or is about to hold, so only a newer
     * revision with no write of ours in flight on that namespace is someone else's change and read again.
     */
    fun onDocumentUpdated(ns: String, revision: Long) {
        val state = _state.value
        val known = state.namespaces[ns]?.revision ?: return
        if (revision <= known || state.writing.keys.any { it.ns == ns }) return
        load()
    }

    /** Set one field. A secret's value goes into the one call and into nothing this class keeps. */
    fun set(field: SettingsField, value: JsonElement) {
        val secret = (value as? JsonPrimitive)?.contentOrNull?.takeIf { field.kind is FieldKind.Secret }
        write(
            field.ns,
            listOf(SettingsPathOpView.Set(listOf(field.key), value)),
            mapOf(field.id to if (secret != null) JsonNull else value),
            secret = secret,
        )
    }

    /** Clear one field's override, so it goes back to the deployment's value. */
    fun reset(field: SettingsField) =
        write(field.ns, listOf(SettingsPathOpView.Unset(listOf(field.key))), mapOf(field.id to null))

    /**
     * Clear the override of every one of [keys] that carries one, in one write: a control that is
     * several fields at once (the web search switch, the model switch with its list) resets as one.
     * Nothing is sent when none of them is overridden.
     */
    fun resetKeys(ns: String, keys: List<String>) {
        val ops = resetOps(_state.value.namespaces[ns], keys)
        if (ops.isEmpty()) return
        val cleared = overriddenKeys(_state.value.namespaces[ns], keys)
        write(ns, ops, cleared.associate { FieldId(ns, it) to null })
    }

    fun dismissFailure(id: FieldId) = _state.update { it.copy(failures = it.failures - id) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun dismissCredentialFailure(ref: String) = _state.update { it.copy(credentialFailures = it.credentialFailures - ref) }

    /** Forget every failure on show, as a page does when it closes: reopened, it should not scold for a change nobody is making. */
    fun clearFailures() = _state.update { it.copy(failures = emptyMap(), credentialFailures = emptyMap()) }

    private fun failuresFor(ids: Set<FieldId>, failure: SettingsFailure): Map<FieldId, FieldFailure> =
        ids.associateWith { FieldFailure(failure, serial.incrementAndGet()) }

    // ------------------------------------------------------------------------ the web-search key

    /** The references the web-search page's key can sit under: each provider section's own. */
    private fun keyRefs(): List<String> {
        val rows = _state.value.namespaces
        if (SettingsNamespaces.WEB_SEARCH !in rows && SettingsNamespaces.WEB_API !in rows) return emptyList()
        return listOf(rows[SettingsNamespaces.WEB_SEARCH], rows[SettingsNamespaces.WEB_API])
            .filter { it != null }
            .map { WebSearchRules.keyRef(it) }
            .distinct()
    }

    /** Ask the credentials domain about the references the page shows; a failed ask only leaves them unknown. */
    fun refreshCredentials() {
        val refs = keyRefs()
        if (refs.isEmpty()) return
        scope.launch {
            when (val result = attempt { port.describeCredentials(refs) }) {
                is RpcResult.Ok -> _state.update { it.copy(credentials = it.credentials + result.value) }
                is RpcResult.Err -> Unit
            }
        }
    }

    /** Store [key] under [ref] through `credentials/set`, as the web page does, then read the slot again. */
    fun saveKey(ref: String, key: String) {
        val value = key.trim()
        if (value.isEmpty() || ref in _state.value.credentialWork) return
        credentialWork(ref, SettingsNotice.KeySaved, secret = value) { port.setCredential(ref, value) }
    }

    /** Remove the stored key; a value from the harness's environment is not this app's to remove. */
    fun removeKey(ref: String) {
        if (ref in _state.value.credentialWork) return
        credentialWork(ref, SettingsNotice.KeyRemoved, secret = null) { port.unsetCredential(ref) }
    }

    private fun credentialWork(ref: String, done: SettingsNotice, secret: String?, call: suspend () -> RpcResult<Unit>) {
        _state.update { it.copy(credentialWork = it.credentialWork + ref, credentialFailures = it.credentialFailures - ref) }
        scope.launch {
            val result = attempt { call() }
            if (result is RpcResult.Ok) {
                // The harness is the authority on whether a key now exists: read the slot, do not assume it.
                val read = attempt { port.describeCredentials(listOf(ref)) }
                _state.update {
                    it.copy(
                        credentials = if (read is RpcResult.Ok) it.credentials + read.value else it.credentials,
                        credentialWork = it.credentialWork - ref,
                        notice = done,
                    )
                }
                // A model provider may use this very key (the DeepSeek one does): what it lists changes with it.
                if (_state.value.catalog !is CatalogState.Idle) loadCatalog()
            } else {
                val failure = (result as RpcResult.Err).error.toSettingsFailure().masked(secret)
                _state.update {
                    it.copy(
                        credentialWork = it.credentialWork - ref,
                        credentialFailures = it.credentialFailures + (ref to FieldFailure(failure, serial.incrementAndGet())),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------------ the model catalog

    /**
     * Read the models the harness offers, for the subagent allowlist. Asked each time the Subagents
     * page opens and again after a refresh or a key change, never once per screen: the answer follows
     * the keys the harness holds. The first read shows progress; a later one keeps the list on
     * screen until the answer replaces it, and a later failure leaves what was read.
     */
    fun loadCatalog() {
        if (catalogJob?.isActive == true) return
        if (_state.value.catalog !is CatalogState.Ready) _state.update { it.copy(catalog = CatalogState.Loading) }
        catalogJob = scope.launch {
            when (val result = attempt { port.modelCatalog() }) {
                is RpcResult.Ok -> _state.update { it.copy(catalog = CatalogState.Ready(result.value)) }
                is RpcResult.Err -> _state.update {
                    if (it.catalog is CatalogState.Ready) it else it.copy(catalog = CatalogState.Failed(result.error.toSettingsFailure()))
                }
            }
        }
    }

    /**
     * Run a call, folding a thrown failure into an error result so a dropped socket is a refusal
     * the screen words, never a crash. Cancellation is not a failure and passes through.
     */
    private suspend fun <T> attempt(call: suspend () -> RpcResult<T>): RpcResult<T> = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RpcResult.Err(RpcError(code = "internal", message = e.message ?: e::class.simpleName.orEmpty()))
    }
}

/** [message] with every occurrence of [secret] replaced, for a harness message that echoes what it was sent. */
fun maskSecret(message: String, secret: String?): String =
    if (secret.isNullOrEmpty()) message else message.replace(secret, "•••")

/** This failure with [secret] taken out of the harness's own words. */
internal fun SettingsFailure.masked(secret: String?): SettingsFailure = when {
    secret.isNullOrEmpty() -> this
    this is SettingsFailure.Rejected -> SettingsFailure.Rejected(maskSecret(detail, secret))
    this is SettingsFailure.Unreachable -> SettingsFailure.Unreachable(maskSecret(detail, secret))
    else -> this
}
