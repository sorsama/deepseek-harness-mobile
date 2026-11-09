package com.labteto.dshmobile.core.wire

import kotlinx.io.IOException

/** What a launch-token exchange produced, or why it did not. */
sealed class SessionExchange {
    /** The `Cookie` header value to send on every later request to this authority. */
    data class Granted(val cookie: String) : SessionExchange()

    /**
     * The harness answered but issued no session.
     *
     * Almost always a token from an earlier harness process: the token rotates on every start and
     * is never persisted, so a startup URL copied yesterday is simply not the current one.
     */
    data class Refused(val status: Int) : SessionExchange()

    /** The exchange never reached a harness. */
    data class Unreachable(val kind: TransportFailure, val message: String?) : SessionExchange()
}

/**
 * The browser-session exchange harness 0.1.2 requires before any `/api` call.
 *
 * From 0.1.2 the harness authenticates its complete API — every Remote call, the mux upgrade, and
 * the session-log download — against a signed cookie, and answers 401 without one. The only way
 * to obtain that cookie is to present the launch token the harness prints once per process to its
 * *index* route: `GET /?token=…`. The token is deliberately refused on `/api` paths and in an
 * `Authorization` header, so there is no alternative to this exchange and no way to fold it into
 * an ordinary call.
 *
 * This is for **direct** connections. Behind `dsh-relay` the relay holds the harness session and
 * injects it upstream, and a phone that sent the host's cookie across the network would be
 * carrying a credential it has no business holding.
 *
 * Nothing here retries. A refusal means the token is not current, and asking again with the same
 * one produces the same answer.
 */
object HarnessSession {

    /** Cookie names the harness issues; the suffix is the normalized authority it is bound to. */
    private const val COOKIE_PREFIX = "dsh-auth-"

    /**
     * Exchange [token] at [baseUrl] for a browser session.
     *
     * Redirects are not followed: a successful exchange answers 3xx to the clean `/`, and the
     * cookie is on *that* response. Letting the HTTP layer chase it would discard the header this
     * whole call exists to read. [http] is a [PlainHttp], which never follows one. On the JVM the
     * same call takes an `OkHttpClient` (`HarnessSession.exchange(baseUrl, token, client)` in
     * `HarnessSessionOkHttp.kt`), so the Android app keeps handing over the client it has.
     */
    suspend fun exchange(
        baseUrl: String,
        token: String,
        http: PlainHttp,
        timeoutMs: Long = 10_000,
    ): SessionExchange {
        val url = resolveHarnessWireUrl(baseUrl, "/").withQueryParameter("token", token)
        // The body is never read: the answer is in the status and the headers.
        val request = PlainHttpRequest(
            method = "GET",
            url = url.text,
            connectTimeoutMs = timeoutMs,
            readTimeoutMs = timeoutMs,
            readBody = false,
        )
        return try {
            val response = http.execute(request)
            val cookie = cookieOf(response.headers("Set-Cookie"))
            if (cookie != null) SessionExchange.Granted(cookie) else SessionExchange.Refused(response.status)
        } catch (e: IOException) {
            SessionExchange.Unreachable(TransportFailures.classify(e), e.message)
        }
    }

    /**
     * The `name=value` of the harness's session cookie among [setCookies].
     *
     * Only that pair travels back; `Path`, `HttpOnly` and `SameSite` are instructions to a browser,
     * and repeating them on a request would be sending the server its own directives. Where a
     * carrier folds repeated `Set-Cookie` headers into one value joined by `", "` (Darwin does), the
     * session cookie is looked for after each such join when no value starts with it.
     */
    private fun cookieOf(setCookies: List<String>): String? {
        setCookies.firstOrNull { it.startsWith(COOKIE_PREFIX) }?.let { return it.substringBefore(';') }
        for (value in setCookies) {
            val joined = value.indexOf(", $COOKIE_PREFIX")
            if (joined >= 0) return value.substring(joined + 2).substringBefore(';')
        }
        return null
    }

    /**
     * Pull a launch token out of whatever the user pasted.
     *
     * People copy the whole startup line, the whole URL, or just the token. Accepting all three
     * costs one function and removes the one step where a working token gets rejected for having
     * a `?token=` in front of it.
     */
    fun tokenFrom(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val marker = trimmed.indexOf("token=")
        val candidate = if (marker >= 0) {
            trimmed.substring(marker + "token=".length).takeWhile { it != '&' && !it.isWhitespace() }
        } else {
            trimmed.takeWhile { !it.isWhitespace() }
        }
        return candidate.takeIf { it.isNotEmpty() }
    }
}
