package com.labteto.dshmobile.models

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.CredentialInfo
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.LlmDiscoveredModel
import com.labteto.dshmobile.core.wire.dto.LlmModelDiscoveryRequest
import com.labteto.dshmobile.core.wire.dto.LlmProviderInfo
import com.labteto.dshmobile.core.wire.dto.ModelCatalog
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import kotlinx.coroutines.CancellationException

/**
 * The harness calls the Models screen makes, and nothing else.
 *
 * A port rather than the client itself so the screen's logic is tested against a fake that
 * records every value it is handed. [setCredential] is the one call a key travels in; the
 * draft check ([discoverModels]) carries it for that one interrogation, which the harness
 * documents as never stored.
 */
interface ModelsPort {
    suspend fun listProviders(): RpcResult<List<LlmProviderInfo>>
    suspend fun listConfigurable(): RpcResult<List<LlmConfigurableProvider>>
    suspend fun describeSettings(): RpcResult<SettingsDescribeValue>
    suspend fun describeCredentials(refs: List<String>): RpcResult<Map<String, CredentialInfo>>
    suspend fun modelCatalog(): RpcResult<ModelCatalog>
    suspend fun mutateSettings(ns: String, ops: List<SettingsPathOpView>, expectedRevision: Long?): RpcResult<SettingsNamespaceView>
    suspend fun setCredential(ref: String, value: String): RpcResult<Unit>
    suspend fun unsetCredential(ref: String): RpcResult<Unit>
    suspend fun discoverModels(settingsNs: String, request: LlmModelDiscoveryRequest): RpcResult<List<LlmDiscoveredModel>>
}

/** The port over a live harness client. */
class DshModelsPort(private val api: DshApiClient) : ModelsPort {
    override suspend fun listProviders() = api.llmListProviders()
    override suspend fun listConfigurable() = api.llmListConfigurableProviders()
    override suspend fun describeSettings() = api.settingsDescribe()
    override suspend fun describeCredentials(refs: List<String>) = api.credentialsDescribe(refs)
    override suspend fun modelCatalog() = api.sessionModelCatalog()
    override suspend fun mutateSettings(ns: String, ops: List<SettingsPathOpView>, expectedRevision: Long?) =
        api.settingsMutate(ns, ops, expectedRevision?.toInt())
    override suspend fun setCredential(ref: String, value: String) = api.credentialsSet(ref, value).unit()
    override suspend fun unsetCredential(ref: String) = api.credentialsUnset(ref).unit()
    override suspend fun discoverModels(settingsNs: String, request: LlmModelDiscoveryRequest) =
        api.llmDiscoverModels(settingsNs, request)

    private fun <T> RpcResult<T>.unit(): RpcResult<Unit> = when (this) {
        is RpcResult.Ok -> RpcResult.Ok(Unit)
        is RpcResult.Err -> this
    }
}

/** The harness has no such call: an older harness, or a relay that keeps these methods to loopback. */
internal fun RpcError.isUnavailable(): Boolean = code in missingMethodCodes

/** The same codes `UsageRepository` reads as "the host has no such method". */
private val missingMethodCodes = setOf(
    "capability-unavailable", "gateway/invocation-unavailable", "gateway/definition-unavailable",
    "method-not-found", "-32601",
)

/** What one directory read answered. */
sealed interface DirectoryLoad {
    data class Loaded(val directory: ProviderDirectory) : DirectoryLoad

    /** The harness does not offer these calls to this app. */
    data object Unsupported : DirectoryLoad

    /** A call failed; [message] is the harness's or the transport's own words. */
    data class Failed(val message: String) : DirectoryLoad
}

/** The harness accepts at most this many references per `credentials/describe`. */
private const val DESCRIBE_BATCH = 64

/**
 * Read the whole directory: the routes, the configurable directory and the settings first, then
 * one batched key describe and, when the account route is listed, the model catalog that says
 * whether its sign-in serves anything. A failed key describe degrades the rows rather than the load.
 */
suspend fun loadProviderDirectory(port: ModelsPort): DirectoryLoad {
    try {
        val registered = when (val r = port.listProviders()) {
            is RpcResult.Ok -> r.value
            is RpcResult.Err -> return r.error.toLoad()
        }
        val configurable = when (val r = port.listConfigurable()) {
            is RpcResult.Ok -> r.value
            is RpcResult.Err -> return r.error.toLoad()
        }
        val settings = when (val r = port.describeSettings()) {
            is RpcResult.Ok -> r.value
            is RpcResult.Err -> return r.error.toLoad()
        }
        val namespaces = settings.namespaces.associateBy { it.ns }
        var rows = joinRows(registered, configurable, namespaces)
        if (rows.any { it.isAccount }) {
            val catalog = port.modelCatalog()
            val signedIn = catalog is RpcResult.Ok &&
                catalog.value.groups.any { it.id == ACCOUNT_PROVIDER && it.models.isNotEmpty() }
            // As the harness page does: the account route is listed only while its sign-in serves
            // models. Signing in is a browser flow this app does not run.
            rows = rows.mapNotNull { row -> if (!row.isAccount) row else row.copy(accountSignedIn = true).takeIf { signedIn } }
        }
        val credentials = mutableMapOf<String, CredentialInfo>()
        var credentialError: String? = null
        for (batch in credentialRefs(rows).chunked(DESCRIBE_BATCH)) {
            when (val r = port.describeCredentials(batch)) {
                is RpcResult.Ok -> credentials.putAll(r.value)
                is RpcResult.Err -> { credentialError = r.error.message; break }
            }
        }
        rows = rows.map { it.copy(credential = credentials[it.keyRef]) }
        return DirectoryLoad.Loaded(ProviderDirectory(rows, settings.writable, credentialError, namespaces))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return DirectoryLoad.Failed(e.message ?: e::class.simpleName.orEmpty())
    }
}

private fun RpcError.toLoad(): DirectoryLoad = if (isUnavailable()) DirectoryLoad.Unsupported else DirectoryLoad.Failed(message)
