package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.serialization.serializer
import kotlinx.serialization.json.*

private suspend inline fun <reified T> DshApiClient.featureCall(endpoint: String, args: JsonObject = JsonObject(emptyMap())): RpcResult<T> =
    call(endpoint, args, serializer<T>())

private fun named(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
private fun str(value: String) = JsonPrimitive(value)
private fun request(value: JsonElement) = named("request" to value)

suspend fun DshApiClient.automationCatalog(): RpcResult<List<AutomationTask>> = featureCall("schedule/catalog")

/**
 * Harness 0.2.2 removed `schedule/update` and `schedule/delete`; `schedule/edit` serves both as one
 * closed operation and compares `expectedUpdatedAt` instead of the complete record 0.2.1 expected.
 */
suspend fun DshApiClient.automationEdit(request: AutomationEditRequest): RpcResult<AutomationMutation> =
    featureCall("schedule/edit", request(WireJson.encodeToJsonElement(AutomationEditRequest.serializer(), request)))

/**
 * Host-wide tasks page, not a Session-scoped list: 0.2.2 dropped the `sessionId` request member and
 * answers `{items,shown,total,limit,offset}`. The app reads the catalog, so this stays for
 * conformance/management parity.
 */
suspend fun DshApiClient.automationList(active: Boolean? = null, limit: Int? = null, offset: Int? = null): RpcResult<ScheduleListResult> =
    featureCall("schedule/list", request(buildJsonObject {
        active?.let { put("active", JsonPrimitive(it)) }
        limit?.let { put("limit", JsonPrimitive(it)) }
        offset?.let { put("offset", JsonPrimitive(it)) }
    }))

/** 0.2.2 addresses saved deliveries by task id alone; the host drops the old `sessionId` member. */
suspend fun DshApiClient.automationHistory(id: String, before: String? = null): RpcResult<AutomationHistory> =
    featureCall("schedule/history", request(buildJsonObject {
        put("id", id); put("limit", 25)
        before?.let { put("before", it) }
    }))

suspend fun DshApiClient.answerContinuedQuestion(sessionId: String, callId: String, answer: AskUserQuestionAnswer): RpcResult<Boolean> =
    featureCall("userQuestions/answer", named("agentId" to str(sessionId), "callId" to str(callId),
        "answer" to WireJson.encodeToJsonElement(AskUserQuestionAnswer.serializer(), answer)))

suspend fun DshApiClient.pluginBundles(): RpcResult<List<PluginBundle>> = featureCall("pluginManager/listBundles")
suspend fun DshApiClient.managedPlugins(): RpcResult<List<ManagedPlugin>> = featureCall("pluginManager/listPlugins")
suspend fun DshApiClient.pluginRegistries(): RpcResult<PluginRegistries> = featureCall("pluginManager/registries")
suspend fun DshApiClient.inspectPlugin(spec: String, registry: String? = null): RpcResult<PluginInspection> =
    featureCall("pluginManager/inspect", buildJsonObject {
        put("spec", spec)
        registry?.let { put("options", named("registry" to str(it))) }
    })
suspend fun DshApiClient.installPlugin(spec: String, options: PluginInstallOptions): RpcResult<PluginChangeResult> =
    featureCall("pluginManager/installBundle", named("spec" to str(spec), "options" to WireJson.encodeToJsonElement(PluginInstallOptions.serializer(), options)))
/**
 * Install [spec], replacing the installed bundle [replaces] when the host will not install over it.
 *
 * Harness 0.2.1-alpha.3 with hot reload refuses to install a package that is already installed
 * (`already-installed`): plugins are stopped by removing the package, so an update is remove and
 * then install. That is what its own plugin manager does for "Update", and what this does when the
 * refusal comes back. A host without hot reload installs over the old version and never refuses,
 * so nothing is removed there. A removal the host defers to the next restart (`stop-profile`) still
 * lets the install go ahead, as the web client does.
 */
suspend fun DshApiClient.installOrReplacePlugin(
    spec: String,
    options: PluginInstallOptions,
    replaces: String?,
): RpcResult<PluginChangeResult> {
    val first = installPlugin(spec, options)
    val refused = (first as? RpcResult.Ok)?.value?.error?.code == "already-installed"
    if (!refused || replaces == null) return first
    val removed = removePluginBundle(replaces)
    if (removed !is RpcResult.Ok) return removed
    val removeError = removed.value.error?.code
    if (removeError != null && removeError != "stop-profile") return removed
    return installPlugin(spec, options)
}
suspend fun DshApiClient.waitForPluginInstall(requestId: String): RpcResult<PluginChangeResult?> =
    featureCall("pluginManager/waitForInstall", named("requestId" to str(requestId)))
suspend fun DshApiClient.cancelPluginInstall(requestId: String): RpcResult<PluginInstallCancellation> =
    featureCall("pluginManager/cancelInstall", named("requestId" to str(requestId)))
suspend fun DshApiClient.setBundleEnabled(name: String, enabled: Boolean): RpcResult<PluginChangeResult> =
    featureCall("pluginManager/setBundleEnabled", named("name" to str(name), "enabled" to JsonPrimitive(enabled)))
suspend fun DshApiClient.setPluginEnabled(id: String, enabled: Boolean): RpcResult<PluginChangeResult> =
    featureCall("pluginManager/setPluginEnabled", named("id" to str(id), "enabled" to JsonPrimitive(enabled)))
suspend fun DshApiClient.removePluginBundle(name: String): RpcResult<PluginChangeResult> =
    featureCall("pluginManager/removeBundle", named("name" to str(name)))
suspend fun DshApiClient.sessionReferenceCandidates(sessionId: String, query: String): RpcResult<List<ReferenceCandidate>> =
    featureCall("sessionReferenceResolver/candidates", named("agentId" to str(sessionId), "query" to str(query)))

class HarnessFeatureException(val code: String, message: String) : Exception(message)
fun <T> RpcResult<T>.featureValue(): T = when (this) {
    is RpcResult.Ok -> value
    is RpcResult.Err -> throw HarnessFeatureException(error.code, error.message)
}
