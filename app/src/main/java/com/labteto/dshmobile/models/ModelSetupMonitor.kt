package com.labteto.dshmobile.models

import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.HarnessClientFactory
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.HostsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which harness the Models screen talks to. */
sealed interface ModelsRequest {
    /** The harness the app is connected to. */
    data object Connected : ModelsRequest

    /** The built-in harness on [port], connected or not. */
    data class BuiltIn(val port: Int) : ModelsRequest
}

/** Where the Models screen sends its calls, and whether a key would cross a network in the clear. */
class ModelsTarget(val port: ModelsPort, val cleartext: Boolean, val hostLabel: String)

/**
 * Whether the harness can answer a prompt: for the harness the app is connected to, and for the
 * built-in one before the app connects to it (the set-up page and the Connect card ask then).
 *
 * The answer comes from the same directory read the Models screen makes, so a hint and the
 * screen can never disagree. It is read again whenever the connection opens and after the
 * screen writes anything; nothing here holds a key or keeps the answer on disk.
 */
@Singleton
class ModelSetupMonitor @Inject constructor(
    private val connection: ConnectionManager,
    private val clientFactory: HarnessClientFactory,
    private val hostsStore: HostsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connected = MutableStateFlow(ModelReadiness.Unknown)

    /** Readiness of the connected harness; Unknown while disconnected or not yet read. */
    val connected: StateFlow<ModelReadiness> = _connected.asStateFlow()

    private val _builtIn = MutableStateFlow<Map<Int, ModelReadiness>>(emptyMap())

    /** Readiness of the harness on this phone, by port, as last probed. */
    val builtIn: StateFlow<Map<Int, ModelReadiness>> = _builtIn.asStateFlow()

    private var refreshing: Job? = null

    private val _request = MutableStateFlow<ModelsRequest?>(null)

    /**
     * Which harness the Models screen is open for, if it is. One host at the app root draws it, so
     * the screen outlives the page it was opened from: the set-up page and the Connect screen leave
     * composition the moment a connection opens, and a key half typed must not go with them.
     */
    val request: StateFlow<ModelsRequest?> = _request.asStateFlow()

    fun open(request: ModelsRequest) {
        _request.value = request
    }

    fun closed() {
        _request.value = null
        changed()
    }

    init {
        scope.launch {
            connection.state
                .map { if (it.phase == ConnectionPhase.CONNECTED) it.host?.authority else null }
                .distinctUntilChanged()
                .collectLatest { authority ->
                    if (authority == null) _connected.value = ModelReadiness.Unknown else readConnected()
                }
        }
    }

    /** Read the connected harness again (after a change, or when a turn says a key is missing). */
    fun refreshConnected() {
        refreshing?.cancel()
        refreshing = scope.launch { readConnected() }
    }

    private suspend fun readConnected() {
        val target = connectedTarget() ?: run { _connected.value = ModelReadiness.Unknown; return }
        _connected.value = read(target.port)
    }

    /** Ask the built-in harness on [port], whether or not the app is connected to it. */
    suspend fun probeBuiltIn(port: Int): ModelReadiness {
        val readiness = try {
            read(builtInTarget(port).port)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ModelReadiness.Unknown
        }
        _builtIn.update { it + (port to readiness) }
        return readiness
    }

    /**
     * Something changed on a harness: every remembered answer is stale, so ask again. The old
     * answers stay on screen until the new ones land, rather than flashing back to "not known".
     */
    fun changed() {
        refreshConnected()
        val ports = _builtIn.value.keys
        if (ports.isNotEmpty()) scope.launch { ports.forEach { probeBuiltIn(it) } }
    }

    /** The connected harness, when there is one. */
    fun connectedTarget(): ModelsTarget? {
        val state = connection.state.value
        if (state.phase != ConnectionPhase.CONNECTED) return null
        val host = state.host ?: return null
        val api = connection.connectedApi ?: return null
        return ModelsTarget(DshModelsPort(api), cleartext = host.sendsInClear(), hostLabel = host.displayAddress)
    }

    /**
     * The built-in harness on [port] through its own signed-in client, for the pages that show
     * before a connection opens. A phone that has not signed in yet gets 401s, which read as Unknown.
     */
    suspend fun builtInTarget(port: Int): ModelsTarget {
        connectedTarget()?.let { target ->
            val host = connection.state.value.host
            if (host != null && host.isLoopback && !host.isRelay && host.port == port) return target
        }
        val config = hostsStore.ensureLoopbackHost(port)
        return ModelsTarget(DshModelsPort(clientFactory.clientFor(config)), cleartext = false, hostLabel = config.displayAddress)
    }

    private suspend fun read(port: ModelsPort): ModelReadiness = when (val load = loadProviderDirectory(port)) {
        is DirectoryLoad.Loaded -> load.directory.readiness()
        else -> ModelReadiness.Unknown
    }
}

/** Whether a key sent to this endpoint crosses a network unencrypted. */
fun HostConfig.sendsInClear(): Boolean = keyTravelsInClear(useTls = useTls, host = host, isLoopback = isLoopback)
