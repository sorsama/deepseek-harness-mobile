package com.labteto.dshmobile.core.wire

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * [PlainHttp] on OkHttp: what the launch-token exchange and the relay calls have always used.
 *
 * Each call runs on [client] with the request's own deadlines and with redirects off (both
 * `followRedirects` and `followSslRedirects`), so a caller's shared client keeps its TLS setup
 * (a pinned or trust-on-first-use relay client) and loses nothing else. The request is enqueued and
 * the coroutine suspended on it, so cancelling the caller cancels the call.
 *
 * A body that fails while it is being read (the peer drops the connection, the read deadline
 * passes) fails the call with that [IOException]. The relay code used to throw it inside OkHttp's
 * callback, where OkHttp logs it and the caller waits for ever.
 */
class OkHttpPlainHttp(private val client: OkHttpClient) : PlainHttp {

    override suspend fun execute(request: PlainHttpRequest): PlainHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newBuilder()
                .connectTimeout(request.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(request.readTimeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
                .newCall(toOkHttp(request))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val answered = try {
                            PlainHttpResponse(
                                status = resp.code,
                                body = if (request.readBody) resp.body?.string().orEmpty() else "",
                                headers = resp.headers.toMultimap(),
                            )
                        } catch (e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                            return
                        }
                        if (continuation.isActive) continuation.resume(answered)
                    }
                }
            })
        }

    private fun toOkHttp(request: PlainHttpRequest): Request {
        val builder = Request.Builder().url(request.url.toHttpUrl())
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        when (request.method) {
            "GET" -> builder.get()
            "POST" -> builder.post(request.body.orEmpty().toRequestBody(request.bodyContentType?.toMediaTypeOrNull()))
            else -> throw IllegalArgumentException("unsupported method ${request.method}")
        }
        return builder.build()
    }
}
