package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LLM-domain DTOs, ported from `packages/host/apiproxy/src/api/llm.schema.ts` and
 * `packages/host/apiproxy/src/api/llm.ts` (v0.1.1-rc.2). The model catalog shapes
 * (ModelProviderGroup / ModelCatalogFailure) live in SessionsDtos.
 */

/** ConfigurableProviderView row of `llm.providers`. */
@Serializable
data class ConfigurableProviderView(
    /** Provider route key this entry activates when configured. */
    @SerialName("provider") val provider: String,
    /** Human-readable provider name for configuration surfaces. */
    @SerialName("displayName") val displayName: String,
    /** User-settings namespace whose section configures this provider. */
    @SerialName("settingsNs") val settingsNs: String,
    /** Path from that namespace's section root to this provider's profile object. */
    @SerialName("settingsPath") val settingsPath: List<String> = emptyList(),
    @SerialName("active") val active: Boolean,
    /** Present when the adapter draws a shipped-vs-declared distinction. */
    @SerialName("declared") val declared: Boolean? = null,
)

/** Value of `llm.providers`. */
@Serializable
data class LlmProvidersValue(
    @SerialName("providers") val providers: List<ConfigurableProviderView> = emptyList(),
)

/** Value of `llm.models`. */
@Serializable
data class LlmModelsValue(
    @SerialName("groups") val groups: List<ModelProviderGroup> = emptyList(),
    @SerialName("failures") val failures: List<ModelCatalogFailure> = emptyList(),
)

/** DiscoveredModelView row of `llm.discoverModels`. */
@Serializable
data class DiscoveredModelView(
    /** Model id the endpoint accepts. */
    @SerialName("id") val id: String,
    /** Human-readable name when the endpoint supplies one. */
    @SerialName("name") val name: String? = null,
    /** Maximum combined request and response context, when disclosed. */
    @SerialName("contextWindow") val contextWindow: Int? = null,
    /** Maximum output tokens, when disclosed. */
    @SerialName("maxTokens") val maxTokens: Int? = null,
)

/** Request payload of `llm.discoverModels` (the draft a user is still editing). */
@Serializable
data class LlmDiscoverModelsRequest(
    /** User-settings namespace the draft edits. */
    @SerialName("settingsNs") val settingsNs: String,
    /** Route the draft is editing, when it edits an existing one. */
    @SerialName("provider") val provider: String? = null,
    /** Endpoint to interrogate. */
    @SerialName("baseURL") val baseURL: String? = null,
    /** Wire protocol the endpoint speaks, when the draft names one. */
    @SerialName("api") val api: String? = null,
    /** Credential for this interrogation alone; the harness never stores it. */
    @SerialName("apiKey") val apiKey: String? = null,
)

/** Value of `llm.discoverModels`. */
@Serializable
data class LlmDiscoverModelsValue(
    @SerialName("models") val models: List<DiscoveredModelView> = emptyList(),
)

// ============================================================================================
// harness 0.1.2 shapes
// ============================================================================================

/**
 * One live provider route, from `llm/listProviders`.
 *
 * 0.1.1 answered live and configurable routes from one `llm.providers` call. 0.1.2 splits them,
 * because they are different facts with different lifecycles: this is what can serve a request
 * now, and [LlmConfigurableProvider] is what an operator may configure. Callers join the two.
 */
@Serializable
data class LlmProviderInfo(
    /** Provider route key used when generating. */
    @SerialName("id") val id: String,
    /** Human-readable provider name for selectors and diagnostics. */
    @SerialName("name") val name: String,
)

/** One configurable provider route, from `llm/listConfigurableProviders`. */
@Serializable
data class LlmConfigurableProvider(
    /** Provider route key this entry activates when configured. */
    @SerialName("provider") val provider: String,
    /** Human-readable provider name for configuration surfaces. */
    @SerialName("displayName") val displayName: String,
    /** User-settings namespace whose section configures this provider. */
    @SerialName("settingsNs") val settingsNs: String,
    /** Path from that namespace's section root to this provider's profile object. */
    @SerialName("settingsPath") val settingsPath: List<String> = emptyList(),
    @SerialName("active") val active: Boolean = false,
    /** Present when the adapter draws a shipped-vs-declared distinction. */
    @SerialName("declared") val declared: Boolean? = null,
)

/**
 * The draft `llm/discoverModels` interrogates.
 *
 * The settings namespace is no longer part of this object: it is a separate named argument, so
 * the request carries only the draft itself.
 */
@Serializable
data class LlmModelDiscoveryRequest(
    /** Route the draft is editing, when it edits an existing one. */
    @SerialName("provider") val provider: String? = null,
    /** Endpoint to interrogate; a route the adapter already describes needs none. */
    @SerialName("baseURL") val baseURL: String? = null,
    /** Wire protocol the endpoint speaks, when the draft names one. */
    @SerialName("api") val api: String? = null,
    /** Credential for this interrogation alone; the harness never stores it. */
    @SerialName("apiKey") val apiKey: String? = null,
)

/** One row of `llm/discoverModels`, which answers a bare array rather than a wrapper object. */
@Serializable
data class LlmDiscoveredModel(
    /** Model id the endpoint accepts. */
    @SerialName("id") val id: String,
    /** Human-readable name when the endpoint supplies one. */
    @SerialName("name") val name: String? = null,
    /** Maximum combined request and response context, when disclosed. */
    @SerialName("contextWindow") val contextWindow: Int? = null,
    /** Maximum output tokens, when disclosed. */
    @SerialName("maxTokens") val maxTokens: Int? = null,
)
