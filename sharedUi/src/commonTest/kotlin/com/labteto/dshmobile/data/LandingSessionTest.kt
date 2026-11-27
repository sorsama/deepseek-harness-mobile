package com.labteto.dshmobile.data

import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.HarnessConnection
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.HostGeneration
import com.labteto.dshmobile.core.wire.MuxSocket
import com.labteto.dshmobile.core.wire.RemoteStreamMux
import com.labteto.dshmobile.core.wire.RpcHttpResponse
import com.labteto.dshmobile.core.wire.RpcTransport
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.dto.SessionListValue
import com.labteto.dshmobile.core.wire.dto.SessionSummary
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertNull
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Source
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The session the app lands in after connecting, against a session the person opened themselves while it was being
 * chosen. The store picks the landing session from the list it has just read and the session remembered for the
 * harness; the person can open one from that same list in the meantime (a tap on a row, or iOS's `-DshOpenSession`
 * launch argument, which waits for the list the same way). Their choice must stand: the landing session is for a
 * person who has not chosen yet.
 *
 * Run against the real [SessionStore] over fake ports. The store works on its own threads, so these tests wait in real
 * time, and the read of the remembered session is held until the test lets it go: that read is the window in which
 * iOS CI saw the landing session replace the one the launch had opened (PR #162, `request-iphone-dark-command`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LandingSessionTest {

    @BeforeTest
    fun mainDispatcher() {
        // The store binds its quota repository on the main dispatcher, which a unit test does not have.
        Dispatchers.setMain(Dispatchers.Default)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a session opened while the landing one is being chosen stays open`() = runTest {
        val rig = Rig()
        withContext(Dispatchers.Default) {
            rig.link.up()
            // The list has been read, which is what puts the rows on screen, and the landing session is being chosen.
            withTimeout(WAIT_MS) { rig.lastSessions.asked.await() }
            assertNull("nothing is open before the landing session is chosen", rig.store.currentSessionId.value)
            rig.store.openSession(PICKED)
            assertEquals(PICKED, rig.store.currentSessionId.value)

            rig.lastSessions.release.complete(Unit)
            // Without the fix the landing session is opened at once, so this is the time it is given to.
            val moved = withTimeoutOrNull(SETTLE_MS) { rig.store.currentSessionId.first { it != PICKED } }
            assertNull("the landing session replaced the one that was opened: $moved", moved)
            assertEquals("the journal followed is the open session's", listOf(PICKED), rig.mux.awaitFollowing(listOf(PICKED)))
            assertEquals("only the opened session is remembered for next time", listOf(PICKED), rig.lastSessions.written())
        }
    }

    @Test
    fun `with nothing opened the landing session opens`() = runTest {
        val rig = Rig(remembered = REMEMBERED)
        withContext(Dispatchers.Default) {
            rig.link.up()
            withTimeout(WAIT_MS) { rig.lastSessions.asked.await() }
            rig.lastSessions.release.complete(Unit)
            withTimeout(WAIT_MS) { rig.store.currentSessionId.first { it == REMEMBERED } }
            assertEquals(listOf(REMEMBERED), rig.mux.awaitFollowing(listOf(REMEMBERED)))
            assertEquals(listOf(REMEMBERED), rig.lastSessions.written())
        }
    }

    @Test
    fun `the newest session is the landing one when none is remembered`() = runTest {
        val rig = Rig(remembered = null)
        withContext(Dispatchers.Default) {
            rig.link.up()
            withTimeout(WAIT_MS) { rig.lastSessions.asked.await() }
            rig.lastSessions.release.complete(Unit)
            withTimeout(WAIT_MS) { rig.store.currentSessionId.first { it == NEWEST } }
            assertEquals(listOf(NEWEST), rig.mux.awaitFollowing(listOf(NEWEST)))
        }
    }

    // ------------------------------------------------------------------ fakes

    private class Rig(remembered: String? = REMEMBERED) {
        val lastSessions = GatedLastSessions(remembered)
        val mux = RecordingMux()
        val link = Link(DshApiClient(ListTransport()), mux.mux)
        val store = SessionStore(Platform(), link, lastSessions)
    }

    private class MemoryStore : KeyValueStore {
        private val lock = SynchronizedObject()
        private val values = HashMap<String, String>()

        override fun getString(key: String): String? = synchronized(lock) { values[key] }

        override fun edit(changes: Map<String, String?>) = synchronized(lock) {
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }

    private class Platform : StorePlatform {
        override val noCurrentGoalMessage = "No goal is set"
        override val migrationRequiredMessage = "Migration required"
        override val subagentReadOnlyMessage = "Read only"
        override val timeZoneId = "UTC"
        override val timeZoneOffsetSeconds = 0
        override val languageTag = "en-US"
        override val language = "en"
        override val appVersion = "0.0.0-test"
        override val pluginOperationStore: KeyValueStore = MemoryStore()
        override val composerDraftStore: KeyValueStore = MemoryStore()

        override fun log(message: String, throwable: Throwable?) = Unit
    }

    /** The remembered session, whose read waits for [release]: the moment between the list and the landing open. */
    private class GatedLastSessions(private val remembered: String?) : LastSessionStore {
        val asked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val lock = SynchronizedObject()
        private val writes = ArrayList<String>()

        fun written(): List<String> = synchronized(lock) { writes.toList() }

        override suspend fun lastSessionId(host: HostConfig): String? {
            asked.complete(Unit)
            release.await()
            return remembered
        }

        override suspend fun setLastSessionId(hostKey: String, sessionId: String) {
            synchronized(lock) { writes += sessionId }
        }
    }

    /** A harness with three sessions; every other call is one it does not serve, as the demo harness answers. */
    private class ListTransport : RpcTransport {
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            val rpcId = WireJson.parseToJsonElement(body).jsonObject["rpcId"]
            val result = if (path.endsWith("/session/list")) {
                """{"ok":true,"value":${WireJson.encodeToString(SessionListValue.serializer(), LIST)}}"""
            } else {
                """{"ok":false,"error":{"code":"gateway/internal","message":"unregistered ${path.removePrefix("/api/")}","details":{}}}"""
            }
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":$rpcId,"result":$result}""")
        }

        override suspend fun <T> download(path: String, consume: (String?, String?, Source) -> T): T = error("unused")

        override suspend fun upload(
            path: String,
            contentType: String,
            contentLength: Long,
            body: Source,
            onProgress: ((Long) -> Unit)?,
        ): RpcHttpResponse = error("unused")
    }

    /** A mux whose streams never answer, recording which session journals are being followed right now. */
    private class RecordingMux {
        private val lock = SynchronizedObject()
        private val follows = LinkedHashMap<String, String>() // streamId -> session followed

        val mux = RemoteStreamMux { sink ->
            object : MuxSocket {
                override fun start() = sink.onOpen()

                override fun close() = Unit

                override fun send(text: String): Boolean {
                    val frame = WireJson.parseToJsonElement(text).jsonObject
                    val streamId = frame["streamId"]?.jsonPrimitive?.content ?: return true
                    synchronized(lock) {
                        when (frame["type"]?.jsonPrimitive?.content) {
                            "open" -> if (frame["endpoint"]?.jsonPrimitive?.content == "session/follow") {
                                follows[streamId] = followedSession(frame["payload"]?.jsonObject)
                            }
                            "cancel" -> follows.remove(streamId)
                        }
                    }
                    return true
                }
            }
        }.also { it.start() }

        private fun followedSession(payload: JsonObject?): String {
            val address = payload?.get("args")?.jsonObject?.get("request")?.jsonObject?.get("address")?.jsonObject
            return address?.get("sessionId")?.jsonPrimitive?.content ?: "?"
        }

        /** The sessions followed once the follow for [expected] is the only one left (a cancel lands a moment later). */
        suspend fun awaitFollowing(expected: List<String>): List<String> {
            fun now() = synchronized(lock) { follows.values.toList() }
            withTimeoutOrNull(WAIT_MS) {
                while (now() != expected) delay(POLL_MS)
            }
            return now()
        }
    }

    private class Link(private val api: DshApiClient, private val mux: RemoteStreamMux) : HarnessConnection {
        private val mutable = MutableStateFlow(ConnectionUiState())
        override val state: StateFlow<ConnectionUiState> = mutable.asStateFlow()
        override var generation: HostGeneration? = null
            private set
        override var connectedApi: DshApiClient? = null
            private set
        override val eventFrames = MutableSharedFlow<RemoteEventFrame>()

        /**
         * The handshake completed: what the real links publish when the ready frame arrives. Not before the store is
         * watching: in the apps the store exists long before anyone connects, and a store that started watching after
         * this would take CONNECTED for where it began and never see a first connection.
         */
        suspend fun up() {
            // Two collectors: the store's connection observer and its quota binding.
            withTimeout(WAIT_MS) { mutable.subscriptionCount.first { it >= STORE_COLLECTORS } }
            val description = HostDescription("/home/demo")
            generation = HostGeneration(description, "client-1", mux)
            connectedApi = api
            mutable.value = ConnectionUiState(
                phase = ConnectionPhase.CONNECTED,
                host = HOST,
                description = description,
                hasConnected = true,
                connectedGeneration = 1,
            )
        }

        override suspend fun connect(config: HostConfig) = Unit

        override fun disconnect() = Unit

        override fun reconnectIfNeeded() = Unit
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 1_500L
        const val POLL_MS = 20L
        const val STORE_COLLECTORS = 2
        const val NEWEST = "demo-01"
        const val REMEMBERED = "demo-03"
        const val PICKED = "demo-05"
        val HOST = HostConfig(id = "host-1", name = "demo", host = "127.0.0.1", port = 3190)
        val LIST = SessionListValue(
            items = listOf(
                SessionSummary(sessionId = NEWEST, updatedAt = 3_000, running = false, blank = false),
                SessionSummary(sessionId = REMEMBERED, updatedAt = 2_000, running = false, blank = false),
                SessionSummary(sessionId = PICKED, updatedAt = 1_000, running = false, blank = false),
            ),
        )
    }
}
