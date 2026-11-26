package com.labteto.dshmobile.mockharness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A harness with something in it: two workspaces, [SESSION_COUNT] sessions, one transcript with markdown, thinking and
 * a tool call (and a second turn that draws every Markdown block the chat renders, [GALLERY]), and a scripted reply to
 * every prompt, streamed the way harness 0.2.x streams one.
 *
 * The iOS simulator scenarios of the session list and the chat run against it (`mock-harness --ci-fixtures` serves it
 * on [CiFixtures.DEMO_PORT]). Everything is fixed except time: the sessions' `updatedAt` and the events' `time` are
 * counted back from [clock] at install, so the list reads "2 hours ago" and not a date years back. A test passes a
 * fixed clock and gets the same bytes every run.
 *
 * What it serves:
 * - `session/list`: the twelve rows, newest first, each titled through its `title` projection and placed in a
 *   workspace through `cwd`.
 * - `workspace/follow`: one baseline frame with the two workspaces and their sessions in order.
 * - `session/control`: an empty baseline (no queue, no jobs).
 * - `session/follow`: an opening snapshot of the session's whole log (no older page), with the assistant baseline
 *   when the follower asked for it.
 * - `session/page`: the records at or below `throughSeq` and below `beforeSeq`, which is always the whole rest.
 * - `session/prompt`: accepted, then the turn plays out on every open follow stream: `turn/start`, the user's
 *   message, an assistant stream ([REPLY] in pieces, [CHUNK_DELAY_MS] apart), the settled `assistant/message`
 *   and `turn/end`. The new events stay in the session's log, so a later follow shows them.
 *
 * A follow stream is not told apart by session in the mock, so a reply is pushed to every open follow; the app
 * follows one session at a time.
 */
class DemoHarness internal constructor(private val harness: MockHarness, private val clock: () -> Long) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val base = clock()

    /** Each session's durable log, as wire events (`{type, seq, time, data}`). */
    private val logs: Map<String, MutableList<JsonObject>> = SESSIONS.associate { it.id to initialLog(it).toMutableList() }

    /** The assistant stream's revision counter, per session. */
    private val revisions = mutableMapOf<String, Int>()

    init {
        harness.remote("session", "list", setOf("_request")) { listValue() }
        harness.onStream("workspace/follow") { listOf(workspaceBaseline()) }
        harness.onStream(SESSION_CONTROL_ENDPOINT) {
            listOf(
                buildJsonObject {
                    put("type", "baseline")
                    putJsonObject("value") {
                        putJsonObject("queues") {}
                        putJsonObject("jobs") {}
                        putJsonObject("projections") {}
                    }
                },
            )
        }
        harness.onStream(SESSION_FOLLOW_ENDPOINT) { args -> listOf(snapshot(args["request"] as? JsonObject)) }
        harness.requestRemote(
            "session",
            "page",
            required = setOf("address", "throughSeq"),
            optional = setOf("beforeSeq", "maxMessages"),
        ) { request -> page(request) }
        harness.requestRemote(
            "session",
            "prompt",
            required = setOf("requestId", "sessionId", "mode", "content"),
            optional = setOf("clientTimeZone"),
        ) { request ->
            harness.sessionPrompts.add(request)
            val sessionId = (request["sessionId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (sessionId in logs) scope.launch { reply(sessionId, request["content"] as? JsonArray ?: JsonArray(emptyList())) }
            buildJsonObject { put("accepted", true) }
        }
    }

    /** The durable log of [sessionId] as it stands, for a test. */
    fun log(sessionId: String): List<JsonObject> = synchronized(lock) { logs[sessionId].orEmpty().toList() }

    internal fun stop() {
        scope.cancel()
    }

    private fun listValue(): JsonObject = buildJsonObject {
        putJsonArray("items") {
            for ((index, session) in SESSIONS.withIndex()) {
                val lastSeq = synchronized(lock) { logs.getValue(session.id).size - 1 }
                addJsonObject {
                    put("sessionId", session.id)
                    put("updatedAt", updatedAt(index))
                    put("running", false)
                    put("blank", false)
                    put("cwd", session.workspace.path)
                    putJsonObject("projections") {
                        put("asOfSeq", lastSeq)
                        putJsonObject("values") { put("title", session.title) }
                    }
                }
            }
        }
    }

    private fun workspaceBaseline(): JsonObject = buildJsonObject {
        put("type", "baseline")
        putJsonArray("workspaces") {
            for (workspace in WORKSPACES) {
                addJsonObject {
                    put("workspaceId", workspace.id)
                    put("path", workspace.path)
                    put("title", workspace.title)
                    putJsonArray("sessionIds") { SESSIONS.filter { it.workspace == workspace }.forEach { add(JsonPrimitive(it.id)) } }
                    put("createdAt", WORKSPACE_CREATED_AT)
                    put("updatedAt", WORKSPACE_CREATED_AT)
                }
            }
        }
        putJsonArray("workspaceIds") { WORKSPACES.forEach { add(JsonPrimitive(it.id)) } }
        putJsonArray("archivedSessionIds") {}
        putJsonArray("pinnedSessionIds") {}
    }

    private fun snapshot(request: JsonObject?): JsonObject {
        val sessionId = sessionIdOf(request)
        val events = synchronized(lock) { logs[sessionId].orEmpty().toList() }
        val assistantStream = (request?.get("assistantStream") as? JsonPrimitive)?.contentOrNull == "true"
        return buildJsonObject {
            put("type", "snapshot")
            put("cursor", events.size - 1)
            put("hasMore", false)
            putJsonObject("header") {
                put("version", 2)
                put("id", sessionId)
                put("createdAt", events.firstOrNull()?.get("time") ?: JsonPrimitive(base))
                put("isSeeded", false)
            }
            putJsonObject("projections") {
                put("asOfSeq", events.size - 1)
                putJsonObject("values") { SESSIONS.firstOrNull { it.id == sessionId }?.let { put("title", it.title) } }
            }
            putJsonArray("records") { events.forEach { add(record(it)) } }
            if (assistantStream) {
                putJsonObject("assistantStream") { put("revision", synchronized(lock) { revisions[sessionId] ?: 0 }) }
            }
        }
    }

    private fun page(request: JsonObject): JsonObject {
        val sessionId = sessionIdOf(request)
        val through = (request["throughSeq"] as? JsonPrimitive)?.intOrNull ?: -1
        val before = (request["beforeSeq"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE
        val events = synchronized(lock) { logs[sessionId].orEmpty().toList() }
        return buildJsonObject {
            putJsonArray("records") {
                events.filter { seqOf(it) in 0..through && seqOf(it) < before }.forEach { add(record(it)) }
            }
            put("hasMore", false)
        }
    }

    /** One prompt's turn, played out on the follow streams. */
    private suspend fun reply(sessionId: String, content: JsonArray) {
        val turn = synchronized(lock) { logs.getValue(sessionId).count { it.type == "turn/start" } + 1 }
        append(sessionId, "turn/start", buildJsonObject { put("turn", turn) })
        append(
            sessionId,
            "user/message",
            buildJsonObject {
                put("id", "$sessionId-u$turn")
                put("role", "user")
                putJsonObject("source") { put("kind", "user") }
                put("content", content)
            },
        )
        val attemptId = "$sessionId:$turn"
        val startedAfter = synchronized(lock) { logs.getValue(sessionId).size - 1 }
        harness.pushAssistantStream(
            buildJsonObject {
                put("type", "start")
                put("attemptId", attemptId)
                put("revision", nextRevision(sessionId))
                put("startedAfterSeq", startedAfter)
                put("turn", turn)
                put("step", 1)
            },
        )
        val pieces = REPLY.chunked(REPLY_PIECE)
        harness.pushAssistantStream(chunkFrame(sessionId, attemptId, 0, buildJsonObject { put("type", "block-start"); put("index", 0); put("blockType", "text") }))
        for ((i, piece) in pieces.withIndex()) {
            delay(CHUNK_DELAY_MS)
            harness.pushAssistantStream(
                chunkFrame(sessionId, attemptId, i + 1, buildJsonObject { put("type", "text-delta"); put("index", 0); put("text", piece) }),
            )
        }
        delay(CHUNK_DELAY_MS)
        harness.pushAssistantStream(
            buildJsonObject {
                put("type", "end")
                put("attemptId", attemptId)
                put("revision", nextRevision(sessionId))
                put("index", pieces.size + 1)
                putJsonObject("outcome") {
                    put("kind", "committed")
                    put("eventType", "assistant/message")
                    put("seq", startedAfter + 1)
                }
            },
        )
        append(sessionId, "assistant/message", assistantMessage(turn, 1, "$sessionId-a$turn", text(REPLY)))
        append(sessionId, "turn/end", buildJsonObject { put("turn", turn); putJsonObject("reason") { put("kind", "completed") } })
    }

    private fun chunkFrame(sessionId: String, attemptId: String, index: Int, chunk: JsonObject) = buildJsonObject {
        put("type", "chunk")
        put("attemptId", attemptId)
        put("revision", nextRevision(sessionId))
        put("index", index)
        put("time", clock())
        put("chunk", chunk)
    }

    private fun nextRevision(sessionId: String): Int = synchronized(lock) {
        val next = (revisions[sessionId] ?: 0) + 1
        revisions[sessionId] = next
        next
    }

    /** Add one durable event to [sessionId]'s log and push it to the follow streams. */
    private suspend fun append(sessionId: String, type: String, data: JsonObject) {
        val event = synchronized(lock) {
            val log = logs.getValue(sessionId)
            wireEvent(type, log.size, clock(), data).also(log::add)
        }
        harness.pushStream(SESSION_FOLLOW_ENDPOINT, record(event))
    }

    private fun updatedAt(index: Int): Long = base - index * SESSION_SPACING_MS - MINUTE_MS

    private fun initialLog(session: DemoSession): List<JsonObject> {
        val index = SESSIONS.indexOf(session)
        val start = updatedAt(index) - 5 * MINUTE_MS
        val events = if (index == 0) richTranscript() + galleryTurn() else shortTranscript(session)
        return events.mapIndexed { seq, (type, data) -> wireEvent(type, seq, start + seq * 7_000L, data) }
    }

    private fun shortTranscript(session: DemoSession): List<Pair<String, JsonObject>> = listOf(
        "turn/start" to buildJsonObject { put("turn", 1) },
        "user/message" to userMessage("${session.id}-u1", session.title + "."),
        "assistant/message" to assistantMessage(1, 1, "${session.id}-a1", text("Done. " + session.summary)),
        "turn/end" to buildJsonObject { put("turn", 1); putJsonObject("reason") { put("kind", "completed") } },
    )

    /** The first session's turn: thinking, a tool call and its result, and an answer in markdown. */
    private fun richTranscript(): List<Pair<String, JsonObject>> {
        val id = SESSIONS.first().id
        val arguments = """{"path":"app/src/main/java/com/example/login/LoginViewModel.kt"}"""
        return listOf(
            "turn/start" to buildJsonObject { put("turn", 1) },
            "user/message" to userMessage("$id-u1", "The app crashes when I tap Log in with an empty password. Can you find out why?"),
            "assistant/message" to assistantMessage(
                1,
                1,
                "$id-a1",
                buildJsonObject { put("type", "reasoning"); put("text", THINKING) },
                text("Let me look at the login view model first."),
                buildJsonObject { put("type", "tool-call"); put("id", "call-1"); put("name", "read_file"); put("arguments", arguments) },
            ),
            "tool/call" to buildJsonObject {
                put("turn", 1)
                put("step", 1)
                put("callId", "call-1")
                put("name", "read_file")
                put("arguments", arguments)
            },
            "tool/result" to buildJsonObject {
                put("turn", 1)
                put("step", 1)
                putJsonObject("message") {
                    put("id", "$id-t1")
                    put("role", "tool")
                    putJsonObject("source") { put("kind", "tool"); put("callId", "call-1") }
                    put("toolCallId", "call-1")
                    put("isError", false)
                    putJsonArray("content") { add(text(TOOL_OUTPUT)) }
                }
            },
            "assistant/message" to assistantMessage(1, 2, "$id-a2", text(ANSWER)),
            "turn/end" to buildJsonObject { put("turn", 1); putJsonObject("reason") { put("kind", "completed") } },
        )
    }

    /**
     * The first session's second turn: one answer with every Markdown block kind, for the chat's pixel comparison and
     * the iOS transcript screenshots. Its thinking is a second panel, so one can be shown open and one closed.
     */
    private fun galleryTurn(): List<Pair<String, JsonObject>> {
        val id = SESSIONS.first().id
        return listOf(
            "turn/start" to buildJsonObject { put("turn", 2) },
            "user/message" to userMessage("$id-u2", GALLERY_PROMPT),
            "assistant/message" to assistantMessage(
                2,
                1,
                "$id-a3",
                buildJsonObject { put("type", "reasoning"); put("text", GALLERY_THINKING) },
                text(GALLERY),
            ),
            "turn/end" to buildJsonObject { put("turn", 2); putJsonObject("reason") { put("kind", "completed") } },
        )
    }

    internal data class DemoWorkspace(val id: String, val title: String, val path: String)

    internal data class DemoSession(val id: String, val title: String, val summary: String, val workspace: DemoWorkspace)

    companion object {
        /** How many sessions `session/list` returns. The iOS scenario expects this count. */
        const val SESSION_COUNT: Int = 12

        /** The answer every prompt gets, streamed in pieces of [REPLY_PIECE] characters. */
        const val REPLY: String =
            "Here is the plan:\n\n1. **Guard** the empty password before hashing.\n2. Show the field's error instead.\n\n" +
                "```kotlin\nif (password.isNullOrEmpty()) return showError()\n```\n\nI can make the change if you want."

        /** Time between two pieces of the streamed reply. */
        const val CHUNK_DELAY_MS: Long = 120

        private const val REPLY_PIECE = 16
        private const val MINUTE_MS = 60_000L
        private const val SESSION_SPACING_MS = 3 * 60 * MINUTE_MS
        private const val WORKSPACE_CREATED_AT = "2026-11-01T09:00:00.000Z"

        private const val THINKING =
            "The crash is on an empty password, so the likely spot is where the password is read. " +
                "A force unwrap or a hash of an empty value would do it. Reading the view model will tell."

        private const val TOOL_OUTPUT =
            "class LoginViewModel {\n    fun submit() {\n        val hash = password!!.sha256()\n        repository.logIn(email, hash)\n    }\n}"

        private const val ANSWER =
            "## Found it\n\n`submit()` reads the password with `!!`, so an empty field throws before anything is checked.\n\n" +
                "- The field starts as `null`, not as an empty string.\n" +
                "- Nothing validates it before the hash.\n\n" +
                "```kotlin\nval hash = password!!.sha256()\n```\n\n" +
                "> A guard before the hash fixes the crash and lets the screen show a proper error."

        private const val GALLERY_PROMPT = "Before you change it, write the whole flow up as notes I can keep."

        private const val GALLERY_THINKING =
            "Notes with a list of steps, a table of the files and the code, so the fix reads in one place."

        /**
         * Every block kind the chat's Markdown draws: three heading levels, bold and italic, an ordered list with a
         * nested bullet list two levels deep, a table with a right-aligned column, a two-paragraph quote, inline code,
         * fenced code with and without a language (the second with lines too long for a phone, so the wrap switch
         * shows), a rule, a web link, a workspace path (drawn as a file pill) and a local address.
         */
        const val GALLERY: String =
            "# Login flow notes\n\n" +
                "The crash is fixed in two places. **Both** matter, and the *order* matters too.\n\n" +
                "## Steps\n\n" +
                "1. The field starts empty, not `null`.\n" +
                "2. `submit()` checks it first:\n" +
                "   - an empty password shows the field's error\n" +
                "   - a short one asks for more:\n" +
                "     - at least 8 characters\n" +
                "     - one of them a digit\n" +
                "3. Only then is the password hashed.\n\n" +
                "### What changes where\n\n" +
                "| File | Change | Lines |\n" +
                "| --- | --- | ---: |\n" +
                "| `LoginViewModel.kt` | guard before the hash | 12 |\n" +
                "| `LoginScreen.kt` | show the error | 4 |\n\n" +
                "> The guard belongs in the view model, so the screen stays a plain view.\n>\n> Tests cover both paths.\n\n" +
                "```kotlin\nfun submit() {\n    val value = password.orEmpty()\n    if (value.isEmpty()) return showError(R.string.login_password_required)\n" +
                "    repository.logIn(email, value.sha256())\n}\n```\n\n" +
                "The build log, as it came:\n\n" +
                "```\n> Task :app:compileDebugKotlin\n" +
                "w: file:///C:/Users/demo/mobile-app/app/src/main/java/com/example/login/LoginViewModel.kt:14:9 'sha256' is deprecated.\n" +
                "BUILD SUCCESSFUL in 41s\n```\n\n" +
                "---\n\n" +
                "See [the view model](app/src/main/java/com/example/login/LoginViewModel.kt), " +
                "the [null safety guide](https://kotlinlang.org/docs/null-safety.html) and the dev server at http://localhost:8080/login."

        internal val WORKSPACES: List<DemoWorkspace> = listOf(
            DemoWorkspace("ws-mobile", "mobile-app", "C:\\Users\\demo\\mobile-app"),
            DemoWorkspace("ws-site", "docs-site", "C:\\Users\\demo\\docs-site"),
        )

        internal val SESSIONS: List<DemoSession> = listOf(
            "Fix the login screen crash" to "The empty password is guarded now.",
            "Add dark mode to settings" to "Settings follows the system theme.",
            "Write tests for the sync queue" to "Eight new tests cover the queue.",
            "Speed up the image cache" to "Thumbnails load from memory first.",
            "Rename the profile API fields" to "The fields use the new names.",
            "Translate the onboarding copy" to "The copy is in four languages.",
            "Explain the build warnings" to "Two warnings were deprecated calls.",
            "Draft the release notes" to "The notes cover the last six changes.",
            "Fix broken links in the guide" to "Three links point to the new pages.",
            "Add a search page" to "The search page lists matching titles.",
            "Update the install steps" to "The steps name the current version.",
            "Tidy up the sidebar styles" to "The sidebar uses the shared spacing.",
        ).mapIndexed { index, (title, summary) ->
            DemoSession(
                id = "demo-" + (index + 1).toString().padStart(2, '0'),
                title = title,
                summary = summary,
                workspace = if (index < 7) WORKSPACES[0] else WORKSPACES[1],
            )
        }.also { check(it.size == SESSION_COUNT) }

        private fun sessionIdOf(request: JsonObject?): String =
            ((request?.get("address") as? JsonObject)?.get("sessionId") as? JsonPrimitive)?.contentOrNull.orEmpty()

        private fun seqOf(event: JsonObject): Int = (event["seq"] as? JsonPrimitive)?.intOrNull ?: -1

        private val JsonObject.type: String? get() = (this["type"] as? JsonPrimitive)?.contentOrNull

        private fun wireEvent(type: String, seq: Int, time: Long, data: JsonElement) = buildJsonObject {
            put("type", type)
            put("seq", seq)
            put("time", time)
            put("data", data)
        }

        /** A durable event as a history record (`{type: "event", event}`), which is what follow and page carry. */
        private fun record(event: JsonObject) = buildJsonObject {
            put("type", "event")
            put("event", event)
        }

        private fun text(value: String) = buildJsonObject {
            put("type", "text")
            put("text", value)
        }

        private fun userMessage(id: String, value: String) = buildJsonObject {
            put("id", id)
            put("role", "user")
            putJsonObject("source") { put("kind", "user") }
            putJsonArray("content") { add(text(value)) }
        }

        private fun assistantMessage(turn: Int, step: Int, id: String, vararg content: JsonObject) = buildJsonObject {
            put("turn", turn)
            put("step", step)
            putJsonObject("message") {
                put("id", id)
                put("role", "assistant")
                putJsonArray("content") { content.forEach { add(it) } }
            }
        }

        /** Serve the demo on [harness]. [clock] is read once for the list's times and again for each new event. */
        fun install(harness: MockHarness, clock: () -> Long = System::currentTimeMillis): DemoHarness = DemoHarness(harness, clock)
    }
}
