package com.labteto.dshmobile.core.uisync

import com.labteto.dshmobile.core.settings.SettingsFailure
import com.labteto.dshmobile.core.settings.toSettingsFailure
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

/**
 * The two settings calls the add-on targets need, on namespace [UiSyncProtocol.NAMESPACE] only. A
 * relay running `privilegedMethods: loopback-only` lets exactly these through for that namespace
 * (relay 0.5.0), so the same port serves a direct connection and a paired one.
 */
interface UiSyncSettingsPort {
    /** `settings/describe` with empty arguments. */
    suspend fun describe(): RpcResult<SettingsDescribeValue>

    /** `settings/mutate` on namespace `ui-sync`, fenced on [expectedRevision] when it is known. */
    suspend fun mutate(ops: List<SettingsPathOpView>, expectedRevision: Long?): RpcResult<SettingsNamespaceView>
}

/** Where the read of the `ui-sync` row stands. */
sealed interface UiSyncSettingsPhase {
    /** Nothing asked for this harness yet. */
    data object Idle : UiSyncSettingsPhase

    /** The first read is on its way. */
    data object Loading : UiSyncSettingsPhase

    /** The row is read; later reads keep it on screen while they run. */
    data object Ready : UiSyncSettingsPhase

    /** The harness answered and has no `ui-sync` namespace: the companion is not installed (or was removed). */
    data object Missing : UiSyncSettingsPhase

    /** The first read failed. */
    data class Failed(val failure: SettingsFailure) : UiSyncSettingsPhase
}

/** A target change that did not land. [serial] changes with every failure, so a screen can tell a new one from one it showed. */
data class UiSyncTargetFailure(val failure: SettingsFailure, val serial: Long)

data class UiSyncSettingsState(
    val phase: UiSyncSettingsPhase = UiSyncSettingsPhase.Idle,
    /** The latest accepted `ui-sync` row. */
    val row: SettingsNamespaceView? = null,
    /** Whether the harness's settings accept writes; false disables every picker. */
    val writable: Boolean = true,
    /** [row] read as items with their targets. */
    val store: UiSyncItemStore = UiSyncItemStore.Empty,
    /** Target changes in flight: what each item reads as until the harness answers. */
    val writing: Map<String, UiSyncTarget> = emptyMap(),
    val failures: Map<String, UiSyncTargetFailure> = emptyMap(),
    /** A later read failed; what is shown is what was read before. Said once, then dismissed. */
    val refreshFailed: SettingsFailure? = null,
) {
    /** The target item [id] shows: the change in flight, else what the harness holds. */
    fun targetOf(id: String): UiSyncTarget? = writing[id] ?: store.items.firstOrNull { it.id == id }?.target

    /**
     * Whether some item would show on the phone, so the add-on page is worth loading: null while
     * that is unknown (not read yet, or the first read failed), false without the companion.
     */
    val phoneItems: Boolean?
        get() = when {
            row != null -> store.worthLoading
            phase == UiSyncSettingsPhase.Missing -> false
            else -> null
        }
}

/**
 * The `ui-sync` row of the connected harness: the item store the companion's pages keep, and the
 * per-item targets Settings > Add-ons writes. One per app process, reset on a host switch.
 *
 * Read with `settings/describe` (the whole answer, kept only for this namespace); written one
 * target at a time with `settings/mutate` `set ["targets", id]`, fenced on the row revision. The
 * pages write the same row (their item store), so a write can meet a `settings/conflict`; it is
 * then read again and tried once more on the new revision. The answer to a write is the new row,
 * which replaces the old one: the screen always shows what the harness holds.
 *
 * The desktop and the phone page pick a new target up from `settings/document-updated` on their
 * own, so nothing is sent to an open page.
 */
class UiSyncSettings(
    private val scope: CoroutineScope,
    private val port: UiSyncSettingsPort,
) {
    private val _state = MutableStateFlow(UiSyncSettingsState())
    val state: StateFlow<UiSyncSettingsState> = _state.asStateFlow()

    /** Writes go one at a time: each is fenced on the revision the one before it produced. */
    private val writes = Mutex()

    /** Bumped by [reset]: an answer that set out before it belongs to another harness and is dropped. */
    private val epoch = atomic(0L)

    /** Bumped by each read: only the newest read that comes back is applied, so an old answer never wins. */
    private val readSerial = atomic(0L)
    private val failureSerial = atomic(0L)

    /** Read the row again, in the background. */
    fun refresh() {
        scope.launch { reload() }
    }

    /**
     * Read the row now and answer with the whole `settings/describe` result, for a caller that also
     * wants to know whether the namespace is there (the feature check).
     */
    suspend fun reload(): RpcResult<SettingsDescribeValue> {
        val startedIn = epoch.value
        val serial = readSerial.incrementAndGet()
        if (_state.value.row == null && _state.value.phase !is UiSyncSettingsPhase.Missing) {
            _state.update { it.copy(phase = UiSyncSettingsPhase.Loading) }
        }
        val result = attempt { port.describe() }
        if (startedIn != epoch.value || serial != readSerial.value) return result
        when (result) {
            is RpcResult.Ok -> {
                val row = result.value.namespaces.firstOrNull { it.ns == UiSyncProtocol.NAMESPACE }
                _state.update {
                    if (row == null) {
                        it.copy(phase = UiSyncSettingsPhase.Missing, row = null, store = UiSyncItemStore.Empty, writable = result.value.writable)
                    } else {
                        it.copy(phase = UiSyncSettingsPhase.Ready, row = row, store = uiSyncItemStore(row.value), writable = result.value.writable)
                    }
                }
            }
            is RpcResult.Err -> {
                val failure = result.error.toSettingsFailure()
                _state.update {
                    if (it.row != null) it.copy(refreshFailed = failure) else it.copy(phase = UiSyncSettingsPhase.Failed(failure))
                }
            }
        }
        return result
    }

    /**
     * Set item [id] to [target]. Shown at once; a refusal puts the item back to what the harness
     * holds and says why under it.
     */
    fun setTarget(id: String, target: UiSyncTarget) {
        val now = _state.value
        if (!now.writable || now.row == null) return
        if (now.writing[id] == target || (id !in now.writing && now.targetOf(id) == target)) return
        val startedIn = epoch.value
        _state.update { it.copy(writing = it.writing + (id to target), failures = it.failures - id) }
        scope.launch {
            writes.withLock {
                // A newer pick for the same item replaced this one while it waited: that one is sent instead.
                if (startedIn != epoch.value || _state.value.writing[id] != target) return@withLock
                val result = write(id, target)
                if (startedIn != epoch.value) return@withLock
                when (result) {
                    is RpcResult.Ok -> _state.update { state ->
                        val accepted = accept(state, result.value)
                        if (accepted.writing[id] == target) accepted.copy(writing = accepted.writing - id) else accepted
                    }
                    is RpcResult.Err -> {
                        val failure = result.error.toSettingsFailure()
                        _state.update { state ->
                            if (state.writing[id] != target) return@update state
                            state.copy(
                                writing = state.writing - id,
                                failures = state.failures + (id to UiSyncTargetFailure(failure, failureSerial.incrementAndGet())),
                            )
                        }
                        // Still in conflict after one retry: what is shown is stale, so read it again.
                        if (failure == SettingsFailure.Conflict) reload()
                    }
                }
            }
        }
    }

    /** One write, and on a conflict one more on the revision a fresh read gives. */
    private suspend fun write(id: String, target: UiSyncTarget): RpcResult<SettingsNamespaceView> {
        val ops = listOf(SettingsPathOpView.Set(listOf(UI_SYNC_TARGETS_FIELD, id), JsonPrimitive(target.wire)))
        val first = attempt { port.mutate(ops, _state.value.row?.revision) }
        if (first !is RpcResult.Err || first.error.toSettingsFailure() != SettingsFailure.Conflict) return first
        if (reload() !is RpcResult.Ok || _state.value.row == null) return first
        return attempt { port.mutate(ops, _state.value.row?.revision) }
    }

    /** A row the harness answered a write with, unless a newer one is already held. */
    private fun accept(state: UiSyncSettingsState, row: SettingsNamespaceView): UiSyncSettingsState {
        if (row.ns != UiSyncProtocol.NAMESPACE) return state
        val held = state.row
        if (held != null && row.revision < held.revision) return state
        return state.copy(phase = UiSyncSettingsPhase.Ready, row = row, store = uiSyncItemStore(row.value))
    }

    /**
     * The harness says [ns] changed (`settings/document-updated`): a page recorded its items, or
     * another client changed a target. Read again unless it is a revision already held, or a write
     * of ours is in flight (its answer carries the row).
     */
    fun onDocumentUpdated(ns: String, revision: Long?) {
        if (ns != UiSyncProtocol.NAMESPACE) return
        val state = _state.value
        if (state.writing.isNotEmpty()) return
        val held = state.row?.revision
        if (held != null && revision != null && revision <= held) return
        refresh()
    }

    fun dismissFailure(id: String) = _state.update { it.copy(failures = it.failures - id) }

    fun dismissRefreshFailed() = _state.update { it.copy(refreshFailed = null) }

    /** Forget every failure on show, as a page does when it closes: reopened, it should not scold for a change nobody is making. */
    fun clearFailures() = _state.update { it.copy(failures = emptyMap(), refreshFailed = null) }

    /** The app switched harness or disconnected: nothing read from the old one applies. */
    fun reset() {
        epoch.incrementAndGet()
        readSerial.incrementAndGet()
        _state.value = UiSyncSettingsState()
    }

    /** A thrown failure (a dropped socket) becomes a refusal the screen can word, never a crash. */
    private suspend fun <T> attempt(call: suspend () -> RpcResult<T>): RpcResult<T> = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RpcResult.Err(RpcError(code = "internal", message = e.message ?: e::class.simpleName.orEmpty()))
    }
}
