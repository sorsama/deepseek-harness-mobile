package com.labteto.dshmobile.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What asking a relay for a web view sign-in code came to. */
sealed interface RelayHandoffOutcome {
    /** Load [enterUrl] in the web view, once, before [expiresAt] (epoch ms, when the relay said). */
    data class Code(val enterUrl: String, val expiresAt: Long?) : RelayHandoffOutcome

    /** 403: the device token is no good any more (unpaired, expired, revoked). Pairing again is the way back. */
    data object Refused : RelayHandoffOutcome

    /** 404 (or 405): a relay older than 0.5.0, with no handoff. Asking again will not help. */
    data object Unsupported : RelayHandoffOutcome

    /** Anything else (a dropped connection, 429, an answer that was not a code): worth asking again later. */
    data class Failed(val reason: String, val retryAfterSeconds: Long? = null) : RelayHandoffOutcome
}

/**
 * The relay's web view session handoff, relay 0.5.0 (`docs/CLIENT_INTEGRATION.md`, "Opening the
 * harness web UI in a WebView"): a web view cannot put the bearer token on the requests its page
 * makes, so the app trades the token for a one-time code, and the view spends the code at
 * `/relay/session/enter`, where the relay sets its `dsh_relay_device` cookie and sends the view on.
 *
 * A code works once, within 60 seconds: ask for a fresh one every time a view is made, and again
 * when the page lands on the relay's sign-in page. Pinned and authorized exactly like the app's own
 * calls to the same relay.
 */
@Singleton
class RelaySessionHandoff @Inject constructor(private val clients: HarnessClientFactory) {
    suspend fun open(config: HostConfig, next: String = "/"): RelayHandoffOutcome {
        if (!config.isRelay) return RelayHandoffOutcome.Unsupported
        val authorization = clients.authorizationFor(config) ?: return RelayHandoffOutcome.Refused
        return openRelaySession(clients.httpClient(config.relayFingerprint), config.baseUrl, authorization, next)
    }
}

/**
 * `POST /relay/session/open` against [baseUrl] with [authorization], and the address the view must
 * load. The code's address is rebuilt from the answer's `path` on [baseUrl]'s own origin, the one
 * the app reached the relay by, rather than taken from `url` (which the relay builds from the
 * `Host` it was sent). Only a path under `/relay/session/enter` is accepted.
 */
internal suspend fun openRelaySession(
    client: OkHttpClient,
    baseUrl: String,
    authorization: String,
    next: String,
): RelayHandoffOutcome = withContext(Dispatchers.IO) {
    val base = baseUrl.toHttpUrlOrNull() ?: return@withContext RelayHandoffOutcome.Failed("bad relay address")
    val url = base.resolve(OPEN_PATH) ?: return@withContext RelayHandoffOutcome.Failed("bad relay address")
    val body = buildJsonObject { put("next", next) }.toString().toRequestBody(JSON)
    val request = Request.Builder()
        .url(url)
        .header("Authorization", authorization)
        .header("Accept", "application/json")
        .post(body)
        .build()
    try {
        client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> {
                    val text = response.body?.string().orEmpty()
                    val answer = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                        ?: return@use RelayHandoffOutcome.Failed("answer was not JSON")
                    val path = (answer["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (path == null || !path.startsWith("$ENTER_PATH?")) return@use RelayHandoffOutcome.Failed("answer had no code path")
                    val enter = base.resolve(path) ?: return@use RelayHandoffOutcome.Failed("bad code path")
                    if (enter.host != base.host || enter.port != base.port || enter.scheme != base.scheme) {
                        return@use RelayHandoffOutcome.Failed("code path left the relay")
                    }
                    RelayHandoffOutcome.Code(enter.toString(), (answer["expiresAt"] as? JsonPrimitive)?.longOrNull)
                }
                401, 403 -> RelayHandoffOutcome.Refused
                404, 405 -> RelayHandoffOutcome.Unsupported
                else -> RelayHandoffOutcome.Failed("relay answered ${response.code}", response.header("Retry-After")?.trim()?.toLongOrNull())
            }
        }
    } catch (e: IOException) {
        RelayHandoffOutcome.Failed(e.javaClass.simpleName)
    }
}

private const val OPEN_PATH = "/relay/session/open"
private const val ENTER_PATH = "/relay/session/enter"
private val JSON = "application/json".toMediaType()
