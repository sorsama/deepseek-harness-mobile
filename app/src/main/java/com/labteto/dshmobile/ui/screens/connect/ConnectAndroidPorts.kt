package com.labteto.dshmobile.ui.screens.connect

import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.DiscoveryEngine
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.MdnsDiscovery
import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.connection.ProbeTimeouts
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.termux.HarnessControl
import com.labteto.dshmobile.termux.TermuxHarnessController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.InetAddress

/*
 * The Android halves of the ports `ConnectController` asks for (`ConnectPorts.kt` in :sharedUi).
 *
 * Each is a thin adapter over a class the app already had, so the data stays where it is: the
 * remembered hosts and the settings are still in `HostsStore` (the same DataStore keys), the sweep is
 * still `DiscoveryEngine`, the live connection is still `ConnectionManager` (which implements
 * `ConnectionLink` itself) and signing in is still `HarnessSessionStore` (which implements
 * `HarnessSignIn`).
 */

/** Hosts and settings, in the app's DataStore. Only the Connect screen's slice of the settings is seen. */
class AndroidConnectStore(private val hostsStore: HostsStore) : ConnectStore {
    override val hosts: Flow<List<HostConfig>> = hostsStore.hosts

    override suspend fun settings(): ConnectSettings = hostsStore.settingsOnce().toConnectSettings()

    override suspend fun update(transform: (ConnectSettings) -> ConnectSettings) {
        hostsStore.setSetting { current ->
            val next = transform(current.toConnectSettings())
            current.copy(
                connectMode = next.connectMode,
                autoConnectLast = next.autoConnectLast,
                autoConnectLan = next.autoConnectLan,
                autoConnectLoopback = next.autoConnectLoopback,
                autoConnectRelay = next.autoConnectRelay,
                autoStartLoopbackHarness = next.autoStartLoopbackHarness,
            )
        }
    }

    override suspend fun setConnectModeIfUnset(default: suspend () -> String): String =
        hostsStore.setConnectModeIfUnset(default)

    override suspend fun rememberHost(
        name: String,
        host: String,
        port: Int,
        isLoopback: Boolean,
        useTls: Boolean,
        description: HostDescription?,
        basePath: String,
    ): HostConfig = hostsStore.rememberHost(
        name = name,
        host = host,
        port = port,
        isLoopback = isLoopback,
        useTls = useTls,
        description = description,
        basePath = basePath,
    )

    override suspend fun cacheDescription(id: String, description: HostDescription) =
        hostsStore.cacheDescription(id, description)

    override suspend fun removeHost(id: String) = hostsStore.removeHost(id)

    override suspend fun addKnownPort(port: Int) = hostsStore.addKnownPort(port)

    override suspend fun ensureLoopbackHost(port: Int): HostConfig = hostsStore.ensureLoopbackHost(port)
}

private fun AppSettings.toConnectSettings() = ConnectSettings(
    connectMode = connectMode,
    autoConnectLast = autoConnectLast,
    autoConnectLan = autoConnectLan,
    autoConnectLoopback = autoConnectLoopback,
    autoConnectRelay = autoConnectRelay,
    autoStartLoopbackHarness = autoStartLoopbackHarness,
    knownPorts = knownPorts,
)

/** The sweep, the probes and mDNS, as the app has always done them. */
class AndroidConnectNetwork(
    private val discovery: DiscoveryEngine,
    private val mdns: MdnsDiscovery,
) : ConnectNetwork {
    override suspend fun probe(host: String, port: Int, timeouts: ProbeTimeouts, config: HostConfig?): HostDescription? =
        discovery.probe(host, port, timeouts, config = config)

    override suspend fun probeOutcome(
        host: String,
        port: Int,
        timeouts: ProbeTimeouts,
        preflight: Boolean,
        useTls: Boolean,
        config: HostConfig?,
        basePath: String,
    ): ProbeOutcome = discovery.probeOutcome(host, port, timeouts, preflight, useTls, config, basePath)

    override suspend fun scan(
        ports: List<Int>,
        onProgress: (probed: Int, total: Int) -> Unit,
        onFound: (DiscoveredHost) -> Unit,
    ): List<DiscoveredHost> = discovery.scan(ports, onProgress, onFound)

    override suspend fun scanForRelays(onFound: (DiscoveredHost) -> Unit): List<DiscoveredHost> =
        discovery.scanForRelays(onFound = onFound)

    override suspend fun browseRelays(onFound: (DiscoveredHost) -> Unit): List<DiscoveredHost> =
        mdns.browse(onFound = onFound)

    override suspend fun isOnLocalSubnet(host: String): Boolean = discovery.isOnLocalSubnet(host)

    override suspend fun localSubnetLabel(): String? = discovery.localSubnetLabel()

    override suspend fun hostLabel(address: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val canonical = InetAddress.getByName(address).canonicalHostName
            canonical.takeIf { it.isNotBlank() && it != address }?.substringBefore('.') ?: address
        }.getOrDefault(address)
    }
}

/** The harness in Termux on this phone. */
class TermuxLoopbackHarness(private val termux: TermuxHarnessController) : LoopbackHarness {
    override val installed: Boolean get() = termux.termuxInstalled

    override fun hasPermission(): Boolean = termux.hasPermission()

    override val state: Flow<HarnessControl> = termux.state

    override fun start(port: Int) = termux.start(port)

    override fun stop(port: Int) = termux.stop(port)

    override fun acknowledge() = termux.acknowledge()
}
