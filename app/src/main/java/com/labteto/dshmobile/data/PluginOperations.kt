package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

data class PluginOperationState(
    val host: String? = null, val requestId: String? = null, val busy: Boolean = false,
    val phase: String? = null, val log: String = "", val result: PluginChangeResult? = null,
    val error: String? = null, val unknown: Boolean = false,
    val subject: String? = null, val registry: String? = null,
    val enabled: Boolean = false, val saveExact: Boolean? = null,
)

/** App-scoped operation ownership: closing Settings never retries or silently cancels an install. */
class PluginOperations(
    private val scope: CoroutineScope,
    private val api: (String?) -> DshApiClient?,
    private val preferences: KeyValueStore? = null,
    private val observe: () -> ((DshApiClient, RpcResult<*>) -> Unit) = { { _, _ -> } },
    private val available: () -> Boolean = { true },
) {
    private suspend fun <T> call(host: String?, block: suspend (DshApiClient) -> RpcResult<T>): T {
        val client = api(host) ?: error("Disconnected")
        val report = observe()
        val result = block(client)
        report(client, result)
        return result.featureValue()
    }
    // Completed mutations and unresolved installs have independent lifetimes.
    private val _state = MutableStateFlow(PluginOperationState())
    val state = _state.asStateFlow()
    private val _pending = MutableStateFlow(restorePending())
    val pending = _pending.asStateFlow()
    private val operations = mutableMapOf<Pair<String, String>, Job>()
    private val attempts = mutableMapOf<Pair<String, String>, Long>()
    private var attempt = 0L
    // Was `@Synchronized` (the instance monitor). Reentrant on purpose: `resume` and `event` call `revise` with it
    // held, and a lazily started attempt can reach `finish` while `install` still holds it.
    private val lock = reentrantLock()

    private fun restorePending(): List<PluginOperationState> {
        val prefs = preferences ?: return emptyList()
        val saved = prefs.getString("pending")
        if (saved != null) return WireJson.parseToJsonElement(saved).jsonArray.map { item ->
            val row = item.jsonObject
            PluginOperationState(host = row.getValue("host").jsonPrimitive.content,
                requestId = row.getValue("requestId").jsonPrimitive.content, unknown = true,
                subject = row["subject"]?.jsonPrimitive?.contentOrNull,
                registry = row["registry"]?.jsonPrimitive?.contentOrNull,
                enabled = row["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                saveExact = row["saveExact"]?.jsonPrimitive?.booleanOrNull)
        }
        val host = prefs.getString("host") ?: return emptyList()
        val id = prefs.getString("requestId") ?: return emptyList()
        return listOf(PluginOperationState(host, id, unknown = true))
    }

    private fun persistPending() {
        val saved = buildJsonArray { _pending.value.forEach { state -> add(buildJsonObject {
            put("host", state.host); put("requestId", state.requestId)
            put("subject", state.subject); put("registry", state.registry)
            put("enabled", state.enabled); put("saveExact", state.saveExact)
        }) } }
        preferences?.edit(mapOf("pending" to saved.toString(), "host" to null, "requestId" to null))
    }

    private fun busy(host: String): Boolean = _pending.value.any { it.host == host && it.busy }

    /** A new attempt invalidates any late result from a cancelled pre-reconnect waiter. */
    private fun launchAttempt(host: String, id: String, block: suspend (Long) -> Unit) {
        val key = host to id
        operations.remove(key)?.cancel()
        val version = ++attempt
        attempts[key] = version
        val job = scope.launch(start = CoroutineStart.LAZY) { block(version) }
        operations[key] = job
        job.start()
    }

    fun install(host: String, spec: String, registry: String?, approvedBuilds: List<String>? = null,
                enabled: Boolean = false, saveExact: Boolean? = null, replaces: String? = null): Unit = lock.withLock {
        if (busy(host) || !available()) return
        val id = newRpcId()
        _pending.value += PluginOperationState(host, id, busy = true, subject = spec, registry = registry,
            enabled = enabled, saveExact = saveExact)
        persistPending()
        launchAttempt(host, id) { version ->
            try { finish(host, id, version, call(host) { it.installOrReplacePlugin(spec, PluginInstallOptions(enabled = enabled, requestId = id, registry = registry, approvedBuilds = approvedBuilds, saveExact = saveExact), replaces) }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { revise(host, id, version) { it.copy(busy = false, unknown = true, error = e.message) } }
        }
    }

    fun resume(host: String, requestId: String? = null): Unit = lock.withLock {
        if (!available()) return
        _pending.value.filter { it.host == host && (requestId == null || it.requestId == requestId) }.forEach { old ->
            val id = old.requestId ?: return@forEach
            revise(host, id) { it.copy(busy = true, unknown = false, error = null) }
            launchAttempt(host, id) { version ->
                try {
                    val result = call(host) { it.waitForPluginInstall(id) }
                    if (result != null) finish(host, id, version, result)
                    else revise(host, id, version) { it.copy(busy = false, unknown = true, error = null) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { revise(host, id, version) { it.copy(busy = false, unknown = true, error = e.message) } }
            }
        }
    }

    private fun revise(host: String, id: String, version: Long? = null, transform: (PluginOperationState) -> PluginOperationState): Unit = lock.withLock {
        if (version != null && attempts[host to id] != version) return
        _pending.value = _pending.value.map { if (it.host == host && it.requestId == id) transform(it) else it }
    }

    fun cancel(host: String, id: String) {
        if (!available()) return
        scope.launch {
            try {
                val result = call(host) { it.cancelPluginInstall(id) }
                // A cancellation receipt is not the install result. Keep waiting for rollback.
                revise(host, id) { it.copy(phase = result.status) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { revise(host, id) { it.copy(error = e.message) } }
        }
    }

    fun changed(host: String, result: PluginChangeResult): Unit = lock.withLock {
        if (!busy(host) && available()) {
            _state.value = PluginOperationState(host = host, result = result)
        }
    }

    private fun finish(host: String, id: String, version: Long, result: PluginChangeResult): Unit = lock.withLock {
        if (attempts[host to id] != version) return
        val old = _pending.value.firstOrNull { it.host == host && it.requestId == id } ?: return
        _pending.value = _pending.value - old
        attempts.remove(host to id); operations.remove(host to id)
        persistPending()
        _state.value = old.copy(busy = false, result = result, unknown = false, error = null)
    }

    /** Explicit local dismissal records no success or failure for an unknown host outcome. */
    fun dismissUnknown(host: String, id: String): Unit = lock.withLock {
        val old = _pending.value.firstOrNull { it.host == host && it.requestId == id && it.unknown && !it.busy } ?: return
        _pending.value = _pending.value - old
        attempts.remove(host to id); operations.remove(host to id)?.cancel()
        persistPending()
    }

    fun event(host: String?, name: String, args: List<JsonElement>): Unit = lock.withLock {
        if (host == null) return
        val value = args.firstOrNull() as? JsonObject ?: return
        val id = value["requestId"]?.jsonPrimitive?.contentOrNull ?: return
        when (name) {
            "plugin-manager/install-log" -> revise(host, id) { it.copy(log = (it.log + value["text"]?.jsonPrimitive?.contentOrNull.orEmpty()).takeLast(32_768)) }
            "plugin-manager/install-state" -> revise(host, id) { it.copy(phase = value["phase"]?.jsonPrimitive?.contentOrNull) }
        }
    }
}
