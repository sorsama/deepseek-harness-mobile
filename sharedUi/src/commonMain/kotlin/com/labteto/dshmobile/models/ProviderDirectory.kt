package com.labteto.dshmobile.models

import com.labteto.dshmobile.core.wire.dto.CredentialInfo
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.LlmProviderInfo
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The harness's model providers, joined the way its own Models page joins them
 * (`packages/client/ui-settings-models/src/client/store.ts`, harness 0.2.2-rc.1): the
 * configurable directory (`llm/listConfigurableProviders`) beside the live routes
 * (`llm/listProviders`), the settings namespaces each provider is configured in
 * (`settings/describe`) and the state of each key reference (`credentials/describe`).
 *
 * The harness stays the one source of every fact here. Nothing in this file holds a key: the
 * credential calls answer whether a key is stored and where it comes from, never the key.
 */

/** The account route; it signs in through a browser and never takes a key. */
const val ACCOUNT_PROVIDER: String = "deepseek-account"

/** The official DeepSeek route, configured by the whole `llm-deepseek` section. */
const val DEEPSEEK_PROVIDER: String = "deepseek-official"

/** The settings namespaces whose profiles this screen knows how to edit. */
enum class ProviderFamily {
    /** `llm-deepseek`: the whole section is the profile; `baseURL` and a fixed key reference. */
    DeepSeek,

    /** `llm-pi-ai`: one profile per route under `providers`; `apiKeyEnv` names the key. */
    PiAi,

    /** Anything else: the key can still be stored, nothing else is edited. */
    Other,
}

fun providerFamilyOf(settingsNs: String): ProviderFamily = when (settingsNs) {
    "llm-deepseek" -> ProviderFamily.DeepSeek
    "llm-pi-ai" -> ProviderFamily.PiAi
    else -> ProviderFamily.Other
}

/** One provider as the screen shows it. */
data class ProviderRow(
    val provider: String,
    val displayName: String,
    val settingsNs: String,
    val settingsPath: List<String>,
    /** The adapter serves this route right now. */
    val active: Boolean,
    /** Some layer configures this provider (its profile resolves). */
    val configured: Boolean,
    /** Only the user layer carries the profile, so removing it restores the shipped state. */
    val removable: Boolean,
    /** The key reference the resolved profile names, when it names one. */
    val apiKeyEnv: String?,
    /** The reference a key typed here is stored under: [apiKeyEnv], or the harness's derived name. */
    val keyRef: String,
    /** State of [keyRef]; null while it could not be described. */
    val credential: CredentialInfo?,
    /** Effective endpoint the profile sets, if any. */
    val baseUrl: String?,
    /** Endpoint the route uses when the profile sets none. */
    val defaultBaseUrl: String?,
    /** Wire protocol the profile names (pi-ai routes). */
    val api: String?,
    /** The adapter only knows this route because configuration declared it. */
    val declared: Boolean,
    /** Configuration diagnostic the harness reports for repair. */
    val error: String?,
    /** For the account route: a sign-in is stored and serves models. */
    val accountSignedIn: Boolean = false,
) {
    val family: ProviderFamily get() = providerFamilyOf(settingsNs)
    val isAccount: Boolean get() = provider == ACCOUNT_PROVIDER

    /** A key is stored for this provider. */
    val keyStored: Boolean get() = credential?.configured == true

    /** The stored key can be replaced or removed from here (it is not from the harness's environment). */
    val keyWritable: Boolean get() = credential?.writable != false

    /** Whether the harness can use this provider for a request as it stands (`providerUsable`). */
    val usable: Boolean
        get() = when {
            !active -> false
            isAccount -> accountSignedIn
            apiKeyEnv == null -> true
            else -> keyStored
        }

    /** A provider the directory knows that nobody has configured: shown under "Add a provider". */
    val dormant: Boolean get() = !configured && !active && !isAccount && settingsPath.isNotEmpty()
}

/** What a row says about itself. */
enum class ProviderStatus { Ready, NeedsKey, SignedIn, NotActive, NotSetUp }

val ProviderRow.status: ProviderStatus
    get() = when {
        isAccount -> ProviderStatus.SignedIn
        usable -> ProviderStatus.Ready
        dormant -> ProviderStatus.NotSetUp
        apiKeyEnv != null && !keyStored -> ProviderStatus.NeedsKey
        else -> ProviderStatus.NotActive
    }

/**
 * The harness's conventional key reference for a route: `deepseek-official` → `DEEPSEEK_OFFICIAL_API_KEY`.
 * Its Models page never asks for a variable name either; a key typed for a profile that names
 * none is stored under this name and the profile records it.
 */
fun deriveKeyRef(provider: String): String =
    provider.uppercase().replace(Regex("[^A-Z0-9]+"), "_") + "_API_KEY"

/** The joined answer the screen and the hints read. */
data class ProviderDirectory(
    val rows: List<ProviderRow>,
    /** The settings document accepts writes. */
    val writable: Boolean,
    /** Describing keys failed; rows stay, their key state is unknown. */
    val credentialError: String?,
    /** Namespace views by id, kept for the revision a write is fenced on. */
    val namespaces: Map<String, SettingsNamespaceView>,
) {
    /** The providers configured or live: the list the screen leads with. */
    val yours: List<ProviderRow> get() = rows.filter { !it.dormant }

    /** Providers the directory offers that nobody set up yet. */
    val addable: List<ProviderRow> get() = rows.filter { it.dormant }

    val anyUsable: Boolean get() = rows.any { it.usable }
}

/** Whether the harness can answer a prompt at all, for the hints outside the screen. */
enum class ModelReadiness {
    /** Not known yet, or the harness could not say (an older harness, a refusal, no connection). */
    Unknown,

    /** At least one provider can serve a request. */
    Ready,

    /** The harness answered, and no provider can serve a request. */
    NoneUsable,
}

fun ProviderDirectory?.readiness(): ModelReadiness = when {
    this == null -> ModelReadiness.Unknown
    anyUsable -> ModelReadiness.Ready
    // With the key state unknown, "none usable" may be wrong; say nothing rather than nag.
    credentialError != null -> ModelReadiness.Unknown
    else -> ModelReadiness.NoneUsable
}

// ---------------------------------------------------------------------------------------- join

/**
 * Join the directory with the live routes, account and official routes first
 * (`joinProviderDirectory`), then each row with its namespace.
 */
internal fun joinRows(
    registered: List<LlmProviderInfo>,
    directory: List<LlmConfigurableProvider>,
    namespaces: Map<String, SettingsNamespaceView>,
): List<ProviderRow> {
    val live = registered.map { it.id }.toSet()
    val declared = directory.map { it.provider }.toSet()
    val entries = directory.map { entry ->
        val ns = namespaces[entry.settingsNs]
        val profile = ns?.value?.at(entry.settingsPath)
        val configured = ns != null && (entry.settingsPath.isEmpty() || (profile != null && profile !is JsonNull))
        val removable = ns != null && entry.settingsPath.isNotEmpty() &&
            ns.user.has(entry.settingsPath) && !ns.base.has(entry.settingsPath)
        val apiKeyEnv = if (entry.provider == ACCOUNT_PROVIDER) null else profile.string("apiKeyEnv")
        ProviderRow(
            provider = entry.provider,
            displayName = entry.displayName,
            settingsNs = entry.settingsNs,
            settingsPath = entry.settingsPath,
            active = entry.provider in live,
            configured = configured,
            removable = removable,
            apiKeyEnv = apiKeyEnv,
            keyRef = apiKeyEnv ?: deriveKeyRef(entry.provider),
            credential = null,
            baseUrl = profile.string("baseURL"),
            defaultBaseUrl = entry.defaultBaseURL,
            api = profile.string("api"),
            declared = entry.declared == true,
            error = entry.error,
        )
    }
    // A live route nothing declares configurable: usable, but there is nothing to edit.
    val undeclared = registered.filter { it.id !in declared }.map { route ->
        ProviderRow(
            provider = route.id, displayName = route.name, settingsNs = "", settingsPath = emptyList(),
            active = true, configured = true, removable = false, apiKeyEnv = null,
            keyRef = deriveKeyRef(route.id), credential = null, baseUrl = null, defaultBaseUrl = null,
            api = null, declared = false, error = null,
        )
    }
    fun rank(row: ProviderRow) = when (row.provider) {
        ACCOUNT_PROVIDER -> 0
        DEEPSEEK_PROVIDER -> 1
        else -> 2
    }
    return (entries + undeclared).sortedBy(::rank)
}

/** The references whose state the rows need: a named reference, or the derived one. */
internal fun credentialRefs(rows: List<ProviderRow>): List<String> =
    rows.filter { !it.isAccount && it.settingsNs.isNotEmpty() }.map { it.keyRef }.distinct()

// ------------------------------------------------------------------------------- JSON paths

/** The value at [path] inside this JSON value, or null when any step is missing. */
internal fun JsonElement?.at(path: List<String>): JsonElement? {
    var node: JsonElement? = this
    for (key in path) node = (node as? JsonObject)?.get(key) ?: return null
    return node
}

internal fun JsonElement?.has(path: List<String>): Boolean {
    if (this == null || this is JsonNull) return false
    val found = at(path)
    return found != null && found !is JsonNull
}

/** A non-blank string field of an object, or null. */
internal fun JsonElement?.string(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

// ------------------------------------------------------------------------------- key rules

/** Why a typed key cannot be saved. */
enum class KeyProblem {
    /** Only spaces. */
    Blank,

    /** Not one printable word: a `NAME=value` line, quotes, spaces or other characters inside. */
    Format,
}

private val LEGAL_KEY = Regex("^[\\x21-\\x7E]+$")
private val ENV_LINE = Regex("^[A-Z][A-Z0-9_]*=[^=]")

/**
 * The harness page's own judgement of a typed key (`apiKey.ts`): printable ASCII with no spaces,
 * and not a pasted `NAME=value` line or a quoted string. An empty field is no problem: it means
 * "keep the stored key". Surrounding spaces from a paste are trimmed before this and before saving.
 */
fun keyProblemOf(draft: String): KeyProblem? {
    if (draft.isEmpty()) return null
    val value = draft.trim()
    if (value.isEmpty()) return KeyProblem.Blank
    if (ENV_LINE.containsMatchIn(value) || isQuoted(value)) return KeyProblem.Format
    if (!LEGAL_KEY.matches(value)) return KeyProblem.Format
    return null
}

private fun isQuoted(value: String): Boolean {
    val first = value.first()
    if (first != '"' && first != '\'' && first != '`') return false
    return value.length > 1 && value.endsWith(first)
}

/** Whether a base URL a person typed is an `http(s)` address. Empty means "use the default". */
fun baseUrlLooksValid(draft: String): Boolean {
    val value = draft.trim()
    if (value.isEmpty()) return true
    val rest = when {
        value.startsWith("https://", ignoreCase = true) -> value.substring(8)
        value.startsWith("http://", ignoreCase = true) -> value.substring(7)
        else -> return false
    }
    return rest.isNotEmpty() && !rest.startsWith("/") && rest.none { it.isWhitespace() }
}

// ------------------------------------------------------------------------------ link rule

/**
 * Whether a key sent to this harness crosses a network in the clear: plain HTTP to anything but
 * this device. A loopback harness (the built-in one, Termux, `adb reverse`) never leaves the
 * phone; TLS (a reverse proxy, a relay with TLS on) encrypts it. A relay running `tls: off` is
 * still plain text.
 */
fun keyTravelsInClear(useTls: Boolean, host: String, isLoopback: Boolean): Boolean =
    !useTls && !isLoopback && !isLoopbackHost(host)

internal fun isLoopbackHost(host: String): Boolean {
    val bare = host.trim().removePrefix("[").removeSuffix("]").lowercase()
    if (bare == "localhost" || bare == "::1" || bare == "0:0:0:0:0:0:0:1") return true
    val parts = bare.split('.')
    return parts.size == 4 && parts[0] == "127" && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
}
