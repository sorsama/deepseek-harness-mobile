package com.labteto.dshmobile.core.wire

import io.ktor.client.engine.darwin.ChallengeHandler
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketCloseCodeInvalid
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketMessageTypeString
import platform.Foundation.NSURLSessionWebSocketTask
import platform.Foundation.NSData
import platform.Foundation.setValue
import platform.darwin.NSObject

/**
 * A bidirectional WebSocket to `/api/remote.mux` written directly on `NSURLSessionWebSocketTask`:
 * the iOS implementation of [MuxSocket], and what the iOS app rides on.
 *
 * It exists because the Ktor Darwin engine's WebSocket (what [KtorMuxSocket] runs on there) has two
 * defects the first simulator run found, both of which are Ktor's wrapper and not `NSURLSession`:
 * a mux upgrade refused with 401 never reached the sink, and messages were capped at 1 MiB because
 * Ktor sets `maximumMessageSize` after the task has resumed. Written on the task itself, this socket
 * can set the limit **before** `resume()` and can read the HTTP status of a refused upgrade off the
 * task's own response. [KtorMuxSocket] stays as the JVM-tested multiplatform reference.
 *
 * The contract is the same as [WsChannel]'s and [KtorMuxSocket]'s, held by `HarnessContractTest` on a
 * simulator against the same fixtures:
 *
 * - [authorization], [cookie] and a hand-spelled `Host` ride on the upgrade request; the target is
 *   `ws(s)://` from the `http(s)` base, as the other carriers build it ([muxTargetOf]). Redirects are
 *   refused, so a credential is never replayed at another address, and there is no cookie storage to
 *   add a second `Cookie`.
 * - [WsChannelSink.onOpen] follows `didOpenWithProtocol`; text messages reach
 *   [WsChannelSink.onMessage] whole and undecoded, one at a time and in order, from a receive loop
 *   that re-arms after each one (binary messages are not part of the mux and are dropped);
 *   [WsChannelSink.onClosed] is `null` on a clean close, a host-initiated one included, and the cause
 *   on a failure.
 * - **A rejected upgrade is an [RpcTransportException]** carrying the real HTTP status (read from
 *   `task.response`) and [carrierMessage]'s text, as `WsChannel` re-wraps OkHttp's `ProtocolException`.
 *   401 (no browser session: re-pair) and 403 (the Host/Origin fence: reconfigure) stay apart.
 * - **Message size:** up to 128 MiB, the limit the other carriers allow, set before `resume()`.
 * - **Deadline:** [connectTimeoutMs] bounds the handshake. `NSURLSession` ignores the request timeout
 *   for a WebSocket task, so the socket keeps its own clock. A refusal is reported when it arrives
 *   (`didCompleteWithError`, with the status on `task.response`), not when the deadline passes. There is
 *   no read deadline once open: the host pings and the system answers.
 * - [send] returns false before [start] and once the socket is gone, accepts messages while the
 *   handshake is in flight, and refuses once more than 16 MiB are waiting, as OkHttp does. [start] and
 *   [close] are idempotent. A socket the caller [close]d reports nothing further: the mux knows.
 *
 * **Threading.** Every sink callback runs on this socket's own serial delegate queue, one at a time and
 * in order, never on the caller's thread, which is the contract `RemoteStreamMux` is written for. The
 * handshake clock hops onto that queue before it decides anything. State shared with callers
 * ([send], [close]) is atomics; nothing here holds a lock while it calls out, so a sink that blocks
 * briefly or a [send] from inside a callback cannot deadlock the socket.
 *
 * **No leak.** `NSURLSession` retains its delegate until it is invalidated, and the delegate reaches
 * this socket, which reaches the session. Every way a socket ends (a failure, a clean close, [close],
 * the deadline) invalidates the session, which breaks that cycle. A socket that is never started never
 * creates one.
 *
 * **Keep-alive.** The harness sends RFC 6455 pings and the peer must answer. `NSURLSessionWebSocketTask`
 * answers them below the API (there is no way to see one: its only ping call, `sendPingWithPongReceiveHandler`,
 * is the client's own), so there is no application-level heartbeat here either. `HarnessContractTest`
 * proves it on a simulator against a server that disconnects a client that does not pong.
 *
 * **Pinning.** [pinnedSpkiSha256] and [observedKey] are the relay's trust decision, applied by the same
 * challenge handlers `darwinHttpClient` gives its unary calls ([pinnedChallengeHandler],
 * [learningChallengeHandler]), so a pinned relay's mux is pinned exactly as its other calls are: the
 * leaf key must match, there is no fallback to system validation, and anything unreadable cancels the
 * challenge. A refused key surfaces as the same [IosPinMismatchException] ([hasPinMismatch]). A pinned
 * socket must be `wss://`: a pin cannot be enforced on a connection that has no TLS, and silently
 * connecting in the clear would turn a typo into a downgrade.
 *
 * @throws IllegalArgumentException for an address that is not a harness's, a blank pin, a pin together
 *   with an [observedKey], or a pin on a plaintext address.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class DarwinMuxSocket(
    url: String,
    private val sink: WsChannelSink,
    private val authorization: String? = null,
    private val cookie: String? = null,
    private val connectTimeoutMs: Long = 30_000,
    pinnedSpkiSha256: String? = null,
    observedKey: ObservedKey? = null,
) : MuxSocket {

    private val target = resolveDarwinMuxTarget(url, pinnedSpkiSha256, observedKey)

    /** What the pinned handler refused, if it did: it records the host before it cancels. */
    private val mismatches: PinMismatchLog? = if (pinnedSpkiSha256 != null) PinMismatchLog() else null

    private val trustHandler: ChallengeHandler? = when {
        pinnedSpkiSha256 != null -> pinnedChallengeHandler(pinnedSpkiSha256, mismatches!!)
        observedKey != null -> learningChallengeHandler(observedKey)
        else -> null
    }

    /** The session, task and queue of a started socket. Present from [start] until the socket ends. */
    private class Live(val session: NSURLSession, val task: NSURLSessionWebSocketTask, val queue: NSOperationQueue)

    private val state = atomic(NEW)
    private val live = atomic<Live?>(null)

    /** The handshake completed (`didOpenWithProtocol`). */
    private val opened = atomic(false)

    /** The host's Close frame arrived (`didCloseWithCode`). */
    private val closeSeen = atomic(false)

    /** The receive loop ended with an error (which is also how a close reaches it). */
    private val receiveEnded = atomic(false)
    private val lastError = atomic<NSError?>(null)

    /** The HTTP status of a rejected upgrade, when a challenge or a redirect carried it before `task.response` did. */
    private val rejectedStatus = atomic(0)

    /** Bytes accepted by [send] and not yet written, against the 16 MiB cap. */
    private val queuedBytes = atomic(0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Called with a line for every delegate event and decision; a test sets it to learn what `NSURLSession` really did. */
    @Volatile
    internal var diagnostics: ((String) -> Unit)? = null

    private fun trace(line: String) {
        diagnostics?.invoke(line)
    }

    override fun start() {
        if (!state.compareAndSet(NEW, RUNNING)) return
        val configuration = NSURLSessionConfiguration.defaultSessionConfiguration()
        // No cookie storage: the `Cookie` header is the only one the server sees, as on every carrier.
        configuration.setHTTPCookieStorage(null)
        // A mux is idle whenever the host has nothing to say (it pings, and the system answers), so the
        // session's own idle and total deadlines must not end it. The handshake has its own clock.
        configuration.timeoutIntervalForRequest = NO_READ_DEADLINE_SECONDS
        configuration.timeoutIntervalForResource = NO_READ_DEADLINE_SECONDS
        val queue = NSOperationQueue()
        // Serial, so the delegate's callbacks and the receive loop's completions arrive in order.
        queue.maxConcurrentOperationCount = 1
        val session = NSURLSession.sessionWithConfiguration(
            configuration,
            delegate = DarwinSocketDelegate(this),
            delegateQueue = queue,
        )
        val task = session.webSocketTaskWithRequest(request())
        // Before `resume()`: `NSURLSession` keeps the 1 MiB it started with if this is set later.
        task.maximumMessageSize = MAX_MESSAGE_BYTES
        val current = Live(session, task, queue)
        live.value = current
        if (state.value != RUNNING) {
            // `close()` ran while this was being built.
            teardown(current)
            return
        }
        task.resume()
        scope.launch { watchHandshake(current) }
    }

    private fun request(): NSMutableURLRequest {
        val request = NSMutableURLRequest.requestWithURL(target.url)
        // No read deadline once open (the host pings); a handshake that never finishes is the clock's job.
        request.setTimeoutInterval(NO_READ_DEADLINE_SECONDS)
        request.setValue(target.hostHeader, forHTTPHeaderField = "Host")
        if (authorization != null) request.setValue(authorization, forHTTPHeaderField = "Authorization")
        if (cookie != null) request.setValue(cookie, forHTTPHeaderField = "Cookie")
        return request
    }

    override fun send(text: String): Boolean {
        if (state.value != RUNNING) return false
        val task = live.value?.task ?: return false
        val size = utf8Length(text).toLong()
        if (queuedBytes.addAndGet(size) > MAX_QUEUE_BYTES) {
            queuedBytes.addAndGet(-size)
            return false
        }
        task.sendMessage(NSURLSessionWebSocketMessage(text)) { error ->
            queuedBytes.addAndGet(-size)
            // A write that failed has failed the connection, and the receive loop and the task's own
            // completion say so; reporting it here too would only race them.
            if (error != null) trace("send failed: ${describe(error)}")
        }
        return true
    }

    override fun close() {
        if (state.getAndSet(DONE) == DONE) return
        live.value?.let { teardown(it) }
    }

    // ---------------------------------------------------------------- delegate events (on the queue)

    internal fun delegateOpened(task: NSURLSessionWebSocketTask) {
        trace("didOpen")
        if (state.value != RUNNING || !opened.compareAndSet(false, true)) return
        guarded("onOpen") { sink.onOpen() }
        if (state.value == RUNNING) receiveNext(task)
    }

    internal fun delegateClosed(code: NSURLSessionWebSocketCloseCode) {
        trace("didClose code=$code")
        closeSeen.value = true
        // The close reaches the receive loop as an error once it has delivered what was already
        // buffered, and that is the moment to report it: deciding here could drop messages that
        // arrived before the Close frame. If the loop never ends, report after a drain period.
        if (receiveEnded.value) settle("didClose") else scheduleSettle(CLOSE_DRAIN_MS, "didClose drain")
    }

    internal fun delegateCompleted(error: NSError?) {
        trace("didComplete error=${error?.let { describe(it) } ?: "none"} response=${statusOfResponse()}")
        if (error != null) lastError.value = error
        settle("didComplete")
    }

    internal fun delegateRedirected(response: NSHTTPURLResponse) {
        // Refused by the delegate (the caller answers `null`), so the redirect itself is the answer.
        val status = response.statusCode.toInt()
        trace("redirect refused status=$status")
        rejectedStatus.value = status
    }

    internal fun delegateChallenged(
        session: NSURLSession,
        task: NSURLSessionTask,
        challenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
    ) {
        val method = challenge.protectionSpace.authenticationMethod
        if (method == NSURLAuthenticationMethodServerTrust) {
            val handler = trustHandler
            trace("server trust challenge, ${if (handler == null) "system evaluation" else "relay key check"}")
            if (handler == null) {
                completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            } else {
                handler(session, task, challenge, completionHandler)
            }
            return
        }
        // HTTP authentication (a 401 with `WWW-Authenticate`, a 407): there is no credential to offer, since
        // the harness is told who this is by the `Authorization` and `Cookie` headers already on the request.
        // Record what the refusal was and end the task, instead of leaving it to find its own way out.
        val status = (challenge.failureResponse as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        trace("auth challenge method=$method status=$status, cancelled")
        if (status != 0) rejectedStatus.value = status
        completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
    }

    // ---------------------------------------------------------------- receiving

    private fun receiveNext(task: NSURLSessionWebSocketTask) {
        task.receiveMessageWithCompletionHandler { message, error -> received(task, message, error) }
    }

    private fun received(task: NSURLSessionWebSocketTask, message: NSURLSessionWebSocketMessage?, error: NSError?) {
        if (state.value != RUNNING) return
        if (error != null || message == null) {
            trace("receive ended error=${error?.let { describe(it) } ?: "none"} closeSeen=${closeSeen.value}")
            if (error != null) lastError.compareAndSet(null, error)
            receiveEnded.value = true
            // A host's Close frame ends the loop with an error as well, in either order with
            // `didCloseWithCode`. If the close is already known this is the end of the data; if not, the
            // task's own completion is about to say what it was, and the short wait is only for the case
            // where it never does.
            if (closeSeen.value) settle("receive ended after close") else scheduleSettle(RECEIVE_ERROR_WAIT_MS, "receive error")
            return
        }
        if (message.type == NSURLSessionWebSocketMessageTypeString) {
            val text = message.string
            if (text != null) guarded("onMessage") { sink.onMessage(text) }
        }
        if (state.value == RUNNING) receiveNext(task)
    }

    // ---------------------------------------------------------------- ending

    /** Decide why the socket ended, from everything the task has said so far, and report it once. */
    private fun settle(via: String) {
        if (state.value != RUNNING) return
        val task = live.value?.task
        val status = statusOfResponse() ?: rejectedStatus.value.takeIf { it != 0 }
        val error = lastError.value
        val cause: Throwable? = when {
            // The upgrade was answered with something other than 101: the status is the whole story.
            !opened.value && status != null && status != HTTP_SWITCHING_PROTOCOLS ->
                RpcTransportException(status, carrierMessage(status), error?.let { failure(it) })
            // The host closed the socket politely.
            closeSeen.value || (task != null && task.closeCode != NSURLSessionWebSocketCloseCodeInvalid) -> null
            error != null -> failure(error)
            // Ended with no error and no Close frame. A clean close is only the host's Close frame, so a
            // connection that just stops is a failure, as OkHttp and `KtorMuxSocket` report it.
            !opened.value -> DarwinSocketException(TransportFailure.OTHER, "the WebSocket closed before it opened")
            else -> DarwinSocketException(TransportFailure.OTHER, "the WebSocket ended without a close frame")
        }
        finish(cause, via)
    }

    private fun failure(error: NSError): Throwable {
        val log = mismatches
        if (log != null && log.explains(target.url.host.orEmpty(), error)) {
            return IosPinMismatchException(
                DarwinSocketException(TransportFailure.CERTIFICATE_PIN, "the relay's key is not the pinned one", error),
            )
        }
        return DarwinSocketException(classifyNSError(error), "the WebSocket failed: ${describe(error)}", error)
    }

    private fun finish(cause: Throwable?, via: String) {
        if (!state.compareAndSet(RUNNING, DONE)) return
        trace("closed via=$via cause=${cause?.let { it::class.simpleName + ": " + it.message } ?: "none (clean close)"}")
        live.value?.let { teardown(it) }
        try {
            sink.onClosed(cause)
        } catch (t: Throwable) {
            // Nothing is left to tell, and an exception out of a delegate callback ends the app.
            trace("onClosed threw ${t::class.simpleName}: ${t.message}")
        }
    }

    private fun teardown(current: Live) {
        scope.cancel()
        current.task.cancel()
        // Releases the delegate, and with it this socket: the end of the retain cycle.
        current.session.invalidateAndCancel()
    }

    /** A failing sink ends the socket, as a throwing OkHttp listener fails the WebSocket, and cannot end the app. */
    private inline fun guarded(what: String, call: () -> Unit) {
        try {
            call()
        } catch (t: Throwable) {
            trace("$what threw ${t::class.simpleName}: ${t.message}")
            finish(t, "$what threw")
        }
    }

    private fun scheduleSettle(afterMs: Long, via: String) {
        val current = live.value ?: return
        scope.launch {
            delay(afterMs)
            current.queue.addOperationWithBlock { settle(via) }
        }
    }

    // ---------------------------------------------------------------- the handshake clock

    /**
     * Bounds the handshake by [connectTimeoutMs]: `NSURLSession` ignores the request's timeout for a
     * WebSocket task, so a handshake that never finishes (a black-holed address) is ended here.
     * The decision is made on the delegate queue, so it is ordered with the delegate's own events.
     */
    private suspend fun watchHandshake(current: Live) {
        if (connectTimeoutMs <= 0) return
        delay(connectTimeoutMs)
        current.queue.addOperationWithBlock { handshakeDeadline() }
    }

    private fun handshakeDeadline() {
        if (state.value != RUNNING || opened.value) return
        finish(
            DarwinSocketException(TransportFailure.TIMEOUT, "the WebSocket handshake did not complete within $connectTimeoutMs ms"),
            "handshake deadline",
        )
    }

    /** The HTTP status the task's response carries, or null while it has none. */
    private fun statusOfResponse(): Int? = (live.value?.task?.response as? NSHTTPURLResponse)?.statusCode?.toInt()

    private fun describe(error: NSError): String = "${error.localizedDescription} (${error.domain} ${error.code})"

    private companion object {
        const val NEW = 0
        const val RUNNING = 1
        const val DONE = 2

        const val HTTP_SWITCHING_PROTOCOLS = 101

        /** The limit the other carriers allow: 128 MiB, which a session's opening snapshot can approach. */
        const val MAX_MESSAGE_BYTES = 128L * 1024 * 1024

        /** OkHttp's own cap on queued outgoing bytes. */
        const val MAX_QUEUE_BYTES = 16L * 1024 * 1024

        /** `NSURLRequest.timeoutInterval` is an idle timeout; a mux is idle whenever the host is quiet. */
        const val NO_READ_DEADLINE_SECONDS = 30.0 * 24 * 3600

        const val RECEIVE_ERROR_WAIT_MS = 300L
        const val CLOSE_DRAIN_MS = 2_000L
    }
}

/** What a mux socket needs to know about where it goes, parsed and checked once. */
internal class DarwinMuxTarget(val hostHeader: String, val url: NSURL)

/**
 * Parse and check the arguments of a [DarwinMuxSocket]; the constructor and [platformMuxSocketFactory]
 * both call it, so a bad address or trust setting surfaces where it was written and not inside the
 * connection loop that builds a socket for every generation.
 */
internal fun resolveDarwinMuxTarget(url: String, pinnedSpkiSha256: String?, observedKey: ObservedKey?): DarwinMuxTarget {
    require(pinnedSpkiSha256 == null || pinnedSpkiSha256.isNotBlank()) { "a certificate pin cannot be blank" }
    require(pinnedSpkiSha256 == null || observedKey == null) { "a socket either enforces a pin or learns a key, not both" }
    val muxTarget = muxTargetOf(url)
    require(pinnedSpkiSha256 == null || muxTarget.target.startsWith("wss://")) {
        "a pinned relay is reached over TLS, and this address is plaintext: $url"
    }
    val nsUrl = NSURL.URLWithString(muxTarget.target) ?: throw IllegalArgumentException("not a WebSocket address: $url")
    return DarwinMuxTarget(muxTarget.hostHeader, nsUrl)
}

/** UTF-8 length of [text] without encoding it: a mux message can be megabytes. */
private fun utf8Length(text: String): Int {
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.code < 0x80 -> bytes += 1
            c.code < 0x800 -> bytes += 2
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                bytes += 4
                i++
            }
            else -> bytes += 3
        }
        i++
    }
    return bytes
}

/**
 * The `NSURLSession` delegate of one [DarwinMuxSocket]: it only forwards, so every decision lives in
 * the socket, in one place. It is the thing `NSURLSession` retains, and the socket invalidates the
 * session to let go of it.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private class DarwinSocketDelegate(private val socket: DarwinMuxSocket) : NSObject(), NSURLSessionWebSocketDelegateProtocol {

    override fun URLSession(session: NSURLSession, webSocketTask: NSURLSessionWebSocketTask, didOpenWithProtocol: String?) {
        socket.delegateOpened(webSocketTask)
    }

    override fun URLSession(
        session: NSURLSession,
        webSocketTask: NSURLSessionWebSocketTask,
        didCloseWithCode: NSURLSessionWebSocketCloseCode,
        reason: NSData?,
    ) {
        socket.delegateClosed(didCloseWithCode)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        socket.delegateCompleted(didCompleteWithError)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
    ) {
        socket.delegateChallenged(session, task, didReceiveChallenge, completionHandler)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        socket.delegateRedirected(willPerformHTTPRedirection)
        completionHandler(null)
    }
}
