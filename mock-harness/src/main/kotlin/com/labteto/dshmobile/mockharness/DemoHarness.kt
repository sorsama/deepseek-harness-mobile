package com.labteto.dshmobile.mockharness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A harness with something in it: two workspaces, [SESSION_COUNT] sessions, one transcript with markdown, thinking and
 * a tool call (and a second turn that draws every Markdown block the chat renders, [GALLERY]), one that calls every
 * kind of tool the chat draws a card for ([TOOL_CARDS_SESSION]), one too long for a single page ([LONG_SESSION]), and a
 * scripted reply to every prompt, streamed the way harness 0.2.x streams one.
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
 * - `session/follow`: an opening snapshot of the session's log, with the assistant baseline when the follower asked
 *   for it. A follower that sends `maxMessages` gets the harness's own cut ([paginate]): the tail holding that many
 *   user and assistant messages, and `hasMore` when older records are left. Only [LONG_SESSION] is long enough to be
 *   cut by the app's page of 60.
 * - `session/page`: the records at or below `throughSeq` and below `beforeSeq`, cut the same way.
 * - `session/modelCatalog`: one provider with two models, so the chat's model chip names one ([MODEL_CATALOG]).
 * - A `permissions` projection on every session ([PERMISSIONS], Workspace Write in force), so the composer's preset chip
 *   and its sheet draw.
 * - `session/prompt`: accepted, then the turn plays out on every open follow stream: `turn/start`, the user's
 *   message, an assistant stream ([REPLY] in pieces, [CHUNK_DELAY_MS] apart), the settled `assistant/message`
 *   and `turn/end`. The new events stay in the session's log, so a later follow shows them. A prompt that starts
 *   with [HOLD_PREFIX] streams the first half of the reply and then holds there, the turn still running, until it is
 *   stopped: a fixed mid-stream state for a screenshot (and for a Stop button to stop). One that starts with
 *   [SLOW_PREFIX] streams the whole reply [SLOW_CHUNK_DELAY_MS] a piece, long enough to be caught mid-stream.
 * - `session/cancel`: stops the session's running turn as the harness's agent loop does on a cancel mid-stream: the
 *   attempt commits what it streamed as the `assistant/message`, and the turn ends `aborted` (`{kind: user}`).
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

    /** The turn each session has playing out, so that `session/cancel` can stop it. */
    private val turns = mutableMapOf<String, Job>()

    /** The assistant stream's revision counter, per session. */
    private val revisions = mutableMapOf<String, Int>()

    init {
        harness.remote("session", "list", setOf("_request")) { listValue() }
        harness.remote("session", "modelCatalog", emptySet()) { Json.parseToJsonElement(MODEL_CATALOG) }
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
            if (sessionId in logs) {
                val job = scope.launch { reply(sessionId, request["content"] as? JsonArray ?: JsonArray(emptyList())) }
                synchronized(lock) { turns[sessionId] = job }
                job.invokeOnCompletion { synchronized(lock) { if (turns[sessionId] === job) turns.remove(sessionId) } }
            }
            buildJsonObject { put("accepted", true) }
        }
        harness.requestRemote("session", "cancel", required = setOf("sessionId")) { request ->
            val sessionId = (request["sessionId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            synchronized(lock) { turns[sessionId] }?.cancel(Stopped())
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
        val (shown, hasMore) = paginate(events, Int.MAX_VALUE, events.size - 1, maxMessagesOf(request))
        return buildJsonObject {
            put("type", "snapshot")
            put("cursor", events.size - 1)
            put("hasMore", hasMore)
            putJsonObject("header") {
                put("version", 2)
                put("id", sessionId)
                put("createdAt", events.firstOrNull()?.get("time") ?: JsonPrimitive(base))
                put("isSeeded", false)
            }
            putJsonObject("projections") {
                put("asOfSeq", events.size - 1)
                putJsonObject("values") {
                    SESSIONS.firstOrNull { it.id == sessionId }?.let { put("title", it.title) }
                    put("permissions", Json.parseToJsonElement(PERMISSIONS))
                }
            }
            putJsonArray("records") { shown.forEach { add(record(it)) } }
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
        val (shown, hasMore) = paginate(events, before, through, maxMessagesOf(request))
        return buildJsonObject {
            putJsonArray("records") { shown.forEach { add(record(it)) } }
            put("hasMore", hasMore)
        }
    }

    /**
     * The harness's own page cut (`paginate` in session-controller's `history.ts`, without the turn window, which the
     * app does not ask for): back from the end (at most [through], below [before]) until [maxMessages] user and
     * assistant messages are in, and `hasMore` when anything older is left. A record's seq is its index in the log.
     */
    private fun paginate(events: List<JsonObject>, before: Int, through: Int, maxMessages: Int): Pair<List<JsonObject>, Boolean> {
        val end = minOf(through + 1, before).coerceIn(0, events.size)
        var count = 0
        var cut = 0
        for (index in end - 1 downTo 0) {
            if (events[index].type !in MESSAGE_TYPES) continue
            count++
            if (count >= maxMessages) {
                cut = index
                break
            }
        }
        return events.subList(cut, end) to (cut > 0)
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
        val prompt = ((content.firstOrNull() as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val holds = prompt.startsWith(HOLD_PREFIX)
        val pace = if (prompt.startsWith(SLOW_PREFIX)) SLOW_CHUNK_DELAY_MS else CHUNK_DELAY_MS
        harness.pushAssistantStream(chunkFrame(sessionId, attemptId, 0, buildJsonObject { put("type", "block-start"); put("index", 0); put("blockType", "text") }))
        var streamed = 0
        try {
            for ((i, piece) in pieces.withIndex()) {
                if (holds && i == pieces.size / 2) awaitCancellation()
                delay(pace)
                harness.pushAssistantStream(
                    chunkFrame(sessionId, attemptId, i + 1, buildJsonObject { put("type", "text-delta"); put("index", 0); put("text", piece) }),
                )
                streamed = i + 1
            }
        } catch (stop: Stopped) {
            // What the harness's agent loop does on a cancel mid-stream: the attempt commits what it streamed, and
            // the turn ends aborted by the user.
            withContext(NonCancellable) {
                harness.pushAssistantStream(
                    buildJsonObject {
                        put("type", "end")
                        put("attemptId", attemptId)
                        put("revision", nextRevision(sessionId))
                        put("index", streamed + 1)
                        putJsonObject("outcome") {
                            put("kind", "committed")
                            put("eventType", "assistant/message")
                            put("seq", startedAfter + 1)
                        }
                    },
                )
                append(sessionId, "assistant/message", assistantMessage(turn, 1, "$sessionId-a$turn", text(pieces.take(streamed).joinToString(""))))
                append(
                    sessionId,
                    "turn/end",
                    buildJsonObject {
                        put("turn", turn)
                        putJsonObject("reason") {
                            put("kind", "aborted")
                            putJsonObject("reason") { put("kind", "user") }
                        }
                    },
                )
            }
            throw stop
        }
        delay(pace)
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
        val events = when (index) {
            0 -> richTranscript() + galleryTurn()
            TOOL_CARDS_INDEX -> toolCardsTranscript(session)
            LONG_INDEX -> longTranscript(session)
            else -> shortTranscript(session)
        }
        return events.mapIndexed { seq, (type, data) -> wireEvent(type, seq, start + seq * 7_000L, data) }
    }

    private fun shortTranscript(session: DemoSession): List<Pair<String, JsonObject>> = listOf(
        "turn/start" to buildJsonObject { put("turn", 1) },
        "user/message" to userMessage("${session.id}-u1", session.title + "."),
        "assistant/message" to assistantMessage(1, 1, "${session.id}-a1", text("Done. " + session.summary)),
        "turn/end" to buildJsonObject { put("turn", 1); putJsonObject("reason") { put("kind", "completed") } },
    )

    /**
     * [LONG_TURNS] short turns, then the session's usual last turn: more user and assistant messages than the app's
     * first page of 60 holds, so the transcript opens on its tail with older history to load, and one page more
     * reaches the start.
     */
    private fun longTranscript(session: DemoSession): List<Pair<String, JsonObject>> {
        val events = mutableListOf<Pair<String, JsonObject>>()
        for (turn in 1..LONG_TURNS) {
            val hits = 40 + turn
            events += "turn/start" to buildJsonObject { put("turn", turn) }
            events += "user/message" to userMessage("${session.id}-u$turn", "Run the thumbnail benchmark again, run $turn.")
            events += "assistant/message" to assistantMessage(
                turn,
                1,
                "${session.id}-a$turn",
                text("Run $turn: $hits% of thumbnails came from memory, the rest from disk."),
            )
            events += "turn/end" to buildJsonObject { put("turn", turn); putJsonObject("reason") { put("kind", "completed") } }
        }
        val last = LONG_TURNS + 1
        events += "turn/start" to buildJsonObject { put("turn", last) }
        events += "user/message" to userMessage("${session.id}-u$last", session.title + ".")
        events += "assistant/message" to assistantMessage(last, 1, "${session.id}-a$last", text("Done. " + session.summary))
        events += "turn/end" to buildJsonObject { put("turn", last); putJsonObject("reason") { put("kind", "completed") } }
        return events
    }

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

    /**
     * The third session's log: one turn that calls every kind of tool the chat draws a card for, with results in the
     * shapes harness 0.2.x records (the durable `meta` the cards are built from, the `error` facts of a refusal), then
     * two commands the reader ran (system lines), a turn stopped with a call still open, and the session's usual last
     * turn, so its tail and its row in the list read as before. For the chat's pixel comparison and the iOS tool-card
     * screenshots.
     */
    private fun toolCardsTranscript(session: DemoSession): List<Pair<String, JsonObject>> {
        val id = session.id
        val queue = "app/src/main/java/com/example/sync/SyncQueue.kt"
        val queueTest = "app/src/test/java/com/example/sync/SyncQueueTest.kt"
        val todos = buildJsonArray {
            add(todo("Read how the queue retries", "in_progress"))
            add(todo("Write the retry tests", "pending"))
            add(todo("Run the sync tests", "pending"))
        }
        val readLines = QUEUE_SOURCE.lines()
        val testOutput = (1..30).joinToString("\n") { "> Task :app:compileStep$it UP-TO-DATE" } +
            "\n\nSyncQueueTest > retries a failed upload three times PASSED" +
            "\nSyncQueueTest > drops an item the server refused PASSED" +
            "\nSyncQueueTest > keeps the order after a restart PASSED" +
            "\n\nBUILD SUCCESSFUL in 38s\n41 actionable tasks: 3 executed, 38 up-to-date"
        val steps = listOf(
            // Step 1: plan and search.
            Step(
                reasoning = "The queue already retries somewhere. Find the retry code before writing tests against it.",
                text = "I'll find the retry code first.",
                calls = listOf(
                    ToolStep("todo_write", buildJsonObject { put("todos", todos) }, "Updated the task list."),
                    ToolStep(
                        "grep",
                        buildJsonObject { put("pattern", "retry"); put("path", "app/src"); put("include", "*.kt") },
                        "Found 3 matches in 2 files.",
                        meta = buildJsonObject {
                            put("shape", "matches"); put("truncated", false); put("total", 3)
                            putJsonArray("files") {
                                add(searchFile(queue, 24 to "    private val retryLimit = 3", 41 to "        if (attempt < retryLimit) retry(item)"))
                                add(searchFile("app/src/main/java/com/example/sync/SyncWorker.kt", 12 to "    override fun shouldRetry() = queue.isNotEmpty()"))
                            }
                        },
                    ),
                    ToolStep(
                        "glob",
                        buildJsonObject { put("pattern", "**/*Sync*.kt") },
                        "3 files.",
                        meta = buildJsonObject {
                            put("shape", "paths"); put("truncated", false); put("total", 3)
                            putJsonArray("paths") {
                                add(JsonPrimitive(queue))
                                add(JsonPrimitive("app/src/main/java/com/example/sync/SyncWorker.kt"))
                                add(JsonPrimitive(queueTest))
                            }
                        },
                    ),
                ),
            ),
            // Step 2: read and look things up.
            Step(
                calls = listOf(
                    ToolStep(
                        "read",
                        buildJsonObject { put("file_path", queue); put("offset", 20); put("limit", readLines.size) },
                        "<path>$queue</path>\n<type>file</type>\n<content>\n" +
                            readLines.mapIndexed { i, line -> "${20 + i}: $line" }.joinToString("\n") + "\n</content>",
                        meta = buildJsonObject {
                            put("path", queue); put("offset", 20); put("totalLines", 88); put("lang", "kotlin")
                            putJsonArray("lines") {
                                readLines.forEachIndexed { i, line -> addJsonObject { put("number", 20 + i); put("text", line) } }
                            }
                        },
                    ),
                    ToolStep(
                        "web_search",
                        buildJsonObject { putJsonArray("queries") { add(JsonPrimitive("kotlin coroutines test retry with virtual time")) } },
                        "2 results.",
                        meta = buildJsonObject {
                            put("truncated", false)
                            put("answer", "Run the code under runTest and advance virtual time past each backoff.")
                            putJsonArray("sources") {
                                addJsonObject {
                                    put("url", "https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/")
                                    put("title", "kotlinx-coroutines-test")
                                    put("snippet", "Test utilities for code that uses coroutines.")
                                }
                                addJsonObject {
                                    put("url", "https://developer.android.com/kotlin/coroutines/test")
                                    put("title", "Testing Kotlin coroutines on Android")
                                    put("snippet", "Use a TestDispatcher to control time.")
                                }
                            }
                        },
                    ),
                    ToolStep(
                        "web_fetch",
                        buildJsonObject { put("url", "https://developer.android.com/kotlin/coroutines/test") },
                        "# Testing Kotlin coroutines on Android\n\nUse runTest and a TestDispatcher to skip delays.",
                        meta = buildJsonObject {
                            put("truncated", false); put("url", "https://developer.android.com/kotlin/coroutines/test"); put("statusCode", 200)
                        },
                    ),
                ),
            ),
            // Step 3: change files and run commands.
            Step(
                text = "Now the tests, and a small fix the first one needs.",
                calls = listOf(
                    ToolStep(
                        "edit",
                        buildJsonObject {
                            put("file_path", queue)
                            put("old_string", "    private val retryLimit = 3\n")
                            put("new_string", "    internal var retryLimit = 3\n        private set\n")
                        },
                        "Edited the file.",
                        meta = buildJsonObject {
                            putJsonArray("diffs") {
                                addJsonObject {
                                    put("path", queue)
                                    put("oldText", "    private val retryLimit = 3\n")
                                    put("newText", "    internal var retryLimit = 3\n        private set\n")
                                }
                            }
                        },
                    ),
                    ToolStep(
                        "write",
                        buildJsonObject { put("file_path", queueTest); put("content", QUEUE_TEST_SOURCE) },
                        "Wrote the file.",
                        meta = buildJsonObject { putJsonArray("diffs") {} },
                    ),
                    ToolStep(
                        "bash",
                        buildJsonObject { put("command", "./gradlew :app:testDebugUnitTest --tests '*SyncQueue*'"); put("description", "Run the sync tests") },
                        testOutput,
                    ),
                    ToolStep(
                        "pwsh",
                        buildJsonObject { put("command", "Get-Content app/build/reports/lint.txt -Tail 3"); put("description", "Show the lint summary") },
                        "Get-Content: Cannot find path 'app/build/reports/lint.txt' because it does not exist.\n[exit code: 1]",
                    ),
                ),
            ),
            // Step 4: helpers, a plugin tool, jobs and a question.
            Step(
                calls = listOf(
                    ToolStep(
                        "subagent",
                        buildJsonObject {
                            put("description", "Check the other workers for the same retry bug")
                            put("prompt", "Look through app/src for workers that retry without a limit and list them.")
                        },
                        "started subagent child-07",
                    ),
                    ToolStep(
                        "skill",
                        buildJsonObject { put("name", "android-testing") },
                        "<skill_content name=\"android-testing\">\n<skill_instructions>\n---\n" +
                            "description: How this project writes and runs unit tests\n---\n# Testing\n\nUse runTest for coroutines.\n" +
                            "</skill_instructions>\n</skill_content>",
                    ),
                    ToolStep(
                        "plugin:notes/save-note",
                        buildJsonObject { put("title", "Sync queue"); put("body", "Retries stop after three attempts.") },
                        "Saved note 12.",
                    ),
                    ToolStep(
                        "job_list",
                        buildJsonObject {},
                        "job-3 [bash] running — ./gradlew assembleDebug\njob-2 [bash] completed — ./gradlew lint",
                    ),
                    ToolStep(
                        "ask_user_question",
                        buildJsonObject {
                            putJsonArray("questions") {
                                addJsonObject {
                                    put("id", "q1")
                                    put("header", "Retries")
                                    put("question", "Should a refused upload be retried later?")
                                    putJsonArray("options") {
                                        addJsonObject { put("label", "No, drop it") }
                                        addJsonObject { put("label", "Yes, once") }
                                    }
                                }
                            }
                        },
                        """{"answers":[{"id":"q1","selected":["No, drop it"]}]}""",
                    ),
                ),
            ),
            // Step 5: what did not run.
            Step(
                calls = listOf(
                    ToolStep(
                        "bash",
                        buildJsonObject { put("command", "rm -rf app/build"); put("description", "Clean the build folder") },
                        "The command was not run.",
                        error = buildJsonObject { put("name", "DshMobilePermissionDenied"); put("code", "APPROVAL_REJECTED_BY_USER") },
                    ),
                    ToolStep(
                        "bash",
                        buildJsonObject { put("command", "git push --force"); put("description", "Push the branch") },
                        "Auto review refused this command.",
                        error = buildJsonObject {
                            put("name", "AutoReviewDeniedError"); put("code", "AUTO_REVIEW_DENIED")
                            put("reason", "A force push can overwrite work someone else pushed.")
                        },
                    ),
                    ToolStep(
                        "edit",
                        buildJsonObject {
                            put("file_path", "app/src/main/java/com/example/sync/SyncWorker.kt")
                            put("old_string", "retry(forever = true)")
                            put("new_string", "retry(limit = 3)")
                        },
                        "Error: old_string was not found in app/src/main/java/com/example/sync/SyncWorker.kt",
                        error = buildJsonObject { put("name", "Error") },
                    ),
                ),
            ),
        )
        val events = mutableListOf<Pair<String, JsonObject>>()
        events += "turn/start" to buildJsonObject { put("turn", 1) }
        events += "user/message" to userMessage("$id-u1", "Write tests for the sync queue's retries.")
        var callNumber = 0
        steps.forEachIndexed { index, step ->
            val stepNumber = index + 1
            val ids = step.calls.map { "$id-call-${++callNumber}" }
            val content = buildList {
                step.reasoning?.let { add(buildJsonObject { put("type", "reasoning"); put("text", it) }) }
                step.text?.let { add(text(it)) }
                step.calls.forEachIndexed { i, c ->
                    add(buildJsonObject { put("type", "tool-call"); put("id", ids[i]); put("name", c.name); put("arguments", c.arguments.toString()) })
                }
            }
            events += "assistant/message" to assistantMessage(1, stepNumber, "$id-a$stepNumber", *content.toTypedArray())
            step.calls.forEachIndexed { i, c ->
                events += "tool/call" to buildJsonObject {
                    put("turn", 1); put("step", stepNumber); put("callId", ids[i]); put("name", c.name); put("arguments", c.arguments.toString())
                }
                if (c.name == "todo_write") events += "todo/write" to buildJsonObject { put("todos", todos) }
                events += "tool/result" to toolResult(1, stepNumber, ids[i], c)
            }
        }
        events += "assistant/message" to assistantMessage(1, steps.size + 1, "$id-a${steps.size + 1}", text(TOOL_CARDS_ANSWER))
        events += "turn/end" to buildJsonObject { put("turn", 1); putJsonObject("reason") { put("kind", "completed") } }
        // Two commands the reader ran: a permission switch and a plain command, each settled.
        events += "command/run" to buildJsonObject {
            put("commandId", "$id-cmd-1"); put("name", "permission"); put("args", " workspace-write")
            putJsonObject("source") { put("kind", "user") }
        }
        events += "command/done" to buildJsonObject { put("commandId", "$id-cmd-1"); put("kind", "success"); put("text", "preset workspace-write") }
        events += "command/run" to buildJsonObject {
            put("commandId", "$id-cmd-2"); put("name", "review")
            putJsonObject("source") { put("kind", "user") }
        }
        events += "command/done" to buildJsonObject { put("commandId", "$id-cmd-2"); put("kind", "success") }
        // A turn the reader stopped while a command was still running: the call has no result.
        val stopped = ToolStep("bash", buildJsonObject { put("command", "./gradlew :app:testDebugUnitTest"); put("description", "Run all unit tests") }, "")
        events += "turn/start" to buildJsonObject { put("turn", 2) }
        events += "user/message" to userMessage("$id-u2", "Run all the unit tests once more.")
        events += "assistant/message" to assistantMessage(
            2, 1, "$id-a7",
            buildJsonObject { put("type", "tool-call"); put("id", "$id-call-stopped"); put("name", stopped.name); put("arguments", stopped.arguments.toString()) },
        )
        events += "tool/call" to buildJsonObject {
            put("turn", 2); put("step", 1); put("callId", "$id-call-stopped"); put("name", stopped.name); put("arguments", stopped.arguments.toString())
        }
        events += "turn/end" to buildJsonObject { put("turn", 2); putJsonObject("reason") { put("kind", "aborted") } }
        // The session's usual last turn, so its tail reads as the other sessions' do.
        events += "turn/start" to buildJsonObject { put("turn", 3) }
        events += "user/message" to userMessage("$id-u3", session.title + ".")
        events += "assistant/message" to assistantMessage(3, 1, "$id-a8", text("Done. " + session.summary))
        events += "turn/end" to buildJsonObject { put("turn", 3); putJsonObject("reason") { put("kind", "completed") } }
        return events
    }

    private class ToolStep(
        val name: String,
        val arguments: JsonObject,
        val output: String,
        val meta: JsonObject? = null,
        /** The `{ name, code, reason? }` the harness records beside a thrown error; marks the result an error. */
        val error: JsonObject? = null,
    )

    private class Step(val calls: List<ToolStep>, val text: String? = null, val reasoning: String? = null)

    private fun toolResult(turn: Int, step: Int, callId: String, call: ToolStep) = buildJsonObject {
        put("turn", turn)
        put("step", step)
        putJsonObject("message") {
            put("id", "$callId-result")
            put("role", "tool")
            putJsonObject("source") { put("kind", "tool"); put("callId", callId) }
            put("toolCallId", callId)
            put("isError", call.error != null)
            putJsonArray("content") { add(text(call.output)) }
        }
        call.meta?.let { put("meta", it) }
        call.error?.let { put("error", it) }
    }

    internal data class DemoWorkspace(val id: String, val title: String, val path: String)

    /** Why a turn was cancelled when `session/cancel` stopped it, rather than the harness shutting down. */
    private class Stopped : CancellationException("stopped by session/cancel")

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

        /** A prompt that starts with this gets half the reply and then a turn that stays running (see the class doc). */
        const val HOLD_PREFIX: String = "Hold"

        /** A prompt that starts with this gets the reply at [SLOW_CHUNK_DELAY_MS] a piece (see the class doc). */
        const val SLOW_PREFIX: String = "Slowly"

        /** Time between two pieces of a [SLOW_PREFIX] reply: about 18 s for the whole of it. */
        const val SLOW_CHUNK_DELAY_MS: Long = 1_500

        /** What `session/modelCatalog` answers: the default route and the one provider that serves it. */
        const val MODEL_CATALOG: String =
            """{"default":{"provider":"deepseek","model":"deepseek-chat"},"routableProviders":["deepseek"],""" +
                """"groups":[{"id":"deepseek","name":"DeepSeek","models":[""" +
                """{"id":"deepseek-chat","name":"DeepSeek-V3.2"},""" +
                """{"id":"deepseek-reasoner","name":"DeepSeek-V3.2 Thinking","reasoning":{"efforts":[""" +
                """{"id":"high","name":"High"},{"id":"max","name":"Max"}],"defaultEffort":"high"}}]}],"failures":[]}"""

        /** Every session's `permissions` projection: the three presets a harness ships, Workspace Write in force. */
        const val PERMISSIONS: String =
            """{"options":[{"value":"read-only","name":"Read only"},{"value":"workspace-write","name":"Workspace Write"},""" +
                """{"value":"danger-full-access","name":"Full access"}],"currentValue":"workspace-write"}"""

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

        /** Which session carries [toolCardsTranscript]: the third, "Write tests for the sync queue". */
        const val TOOL_CARDS_INDEX: Int = 2

        /** The id of the session that carries every tool card. */
        val TOOL_CARDS_SESSION: String get() = SESSIONS[TOOL_CARDS_INDEX].id

        /** Which session carries [longTranscript]: the fourth, "Speed up the image cache". */
        const val LONG_INDEX: Int = 3

        /** The id of the session whose history is longer than one page. */
        val LONG_SESSION: String get() = SESSIONS[LONG_INDEX].id

        /** The short turns before [LONG_SESSION]'s last: 46 turns, 92 messages, so a page of 60 leaves 32 older. */
        const val LONG_TURNS: Int = 45

        /** The events the harness counts toward a page's `maxMessages`. */
        private val MESSAGE_TYPES = setOf("user/message", "assistant/message")

        /** The answer that settles the tool-card turn. */
        const val TOOL_CARDS_ANSWER: String =
            "The retry tests are in and pass: **three** new cases in `SyncQueueTest`.\n\n" +
                "Two things did not run: you said no to cleaning the build folder, and auto review stopped the force push."

        private const val QUEUE_SOURCE: String =
            "class SyncQueue(private val api: SyncApi) {\n" +
                "    private val items = ArrayDeque<SyncItem>()\n" +
                "\n" +
                "    private val retryLimit = 3\n" +
                "\n" +
                "    suspend fun drain() {\n" +
                "        while (items.isNotEmpty()) {\n" +
                "            val item = items.removeFirst()\n" +
                "            val result = api.upload(item)\n" +
                "            if (result.refused) continue\n" +
                "            if (!result.ok) retry(item)\n" +
                "        }\n" +
                "    }"

        private const val QUEUE_TEST_SOURCE: String =
            "package com.example.sync\n\n" +
                "class SyncQueueTest {\n" +
                "    @Test\n" +
                "    fun `retries a failed upload three times`() = runTest {\n" +
                "        val api = FakeSyncApi(failures = 5)\n" +
                "        SyncQueue(api).apply { add(item()) }.drain()\n" +
                "        assertEquals(3, api.attempts)\n" +
                "    }\n" +
                "}\n"

        private fun todo(content: String, status: String) = buildJsonObject {
            put("content", content)
            put("status", status)
        }

        private fun searchFile(path: String, vararg matches: Pair<Int, String>) = buildJsonObject {
            put("path", path)
            putJsonArray("matches") {
                for ((line, text) in matches) addJsonObject { put("lineNumber", line); put("line", text) }
            }
        }

        private fun sessionIdOf(request: JsonObject?): String =
            ((request?.get("address") as? JsonObject)?.get("sessionId") as? JsonPrimitive)?.contentOrNull.orEmpty()

        /** A request's `maxMessages`, or no limit when it sends none (the whole log, as before). */
        private fun maxMessagesOf(request: JsonObject?): Int =
            (request?.get("maxMessages") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: Int.MAX_VALUE

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
