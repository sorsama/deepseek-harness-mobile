package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** One call-keyed card; the host projection, rather than the countdown, decides settlement. */
data class QuestionCardState(
    val sessionId: String,
    val callId: String,
    val questions: List<AskUserQuestionItem>,
    val state: String = "open",
    val remainingMs: Long? = null,
    val held: Boolean = false,
    val focused: Boolean = false,
    val hidden: Boolean = false,
    val ready: Boolean = false,
    val queued: Boolean = false,
    val answers: List<AskUserQuestionAnswerItem>? = null,
    val needsAction: Boolean = true,
) { val key: String get() = "$sessionId/$callId" }

/** Session rows combine these kinds with legacy requests without sharing their lifetimes. */
internal fun List<QuestionCardState>.pendingQuestionKinds(): Map<String, Set<String>> =
    filter { it.state == "open" && it.needsAction }.groupBy { it.sessionId }.mapValues { (_, cards) ->
        cards.map { card ->
            if (card.questions.any { it.intent is AskUserQuestionIntent.PlanReview }) "plan-review" else "question"
        }.toSet()
    }

/** Owns live claims across navigation and reconciles late answers against durable projections. */
class QuestionSessions(
    private val scope: CoroutineScope,
    private val api: () -> DshApiClient?,
    private val mux: () -> RemoteStreamMux?,
    private val generation: () -> String?,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onCapability: (String?, RpcResult<*>) -> Unit = { _, _ -> },
    private val onUnavailable: (String, String, List<AskUserQuestionItem>) -> Unit = { _, _, _ -> },
) {
    private data class Request(val eventId: String, val generation: String, val job: Job?)
    // Reentrant on purpose: `change` takes this lock and is called from inside it (`updateClaim`,
    // `cancelled`, the answer paths), which the JVM monitor this replaced allowed.
    private val lock = reentrantLock()
    private val requests = mutableMapOf<String, Request>()
    private var epoch = 0L
    private val versions = mutableMapOf<String, Int>()
    private var revision = 0L
    private val revisions = mutableMapOf<String, Long>()
    private val _cards = MutableStateFlow<List<QuestionCardState>>(emptyList())
    val cards = _cards.asStateFlow()

    fun reset() = lock.withLock {
        epoch++
        requests.values.forEach { it.job?.cancel() }; requests.clear(); versions.clear(); revisions.clear(); _cards.value = emptyList()
    }

    fun disconnect() = lock.withLock {
        epoch++
        requests.values.forEach { it.job?.cancel() }; requests.clear(); versions.clear(); revisions.clear()
        _cards.value = _cards.value.map { it.copy(ready = false, remainingMs = null) }
    }

    private fun change(key: String, transform: (QuestionCardState) -> QuestionCardState) = lock.withLock {
        _cards.value = _cards.value.map { if (it.key == key) transform(it) else it }
    }

    fun removeSession(sessionId: String) = lock.withLock {
        val removed = _cards.value.filter { it.sessionId == sessionId }
        removed.forEach { requests.remove(it.key)?.job?.cancel() }
        versions.remove(sessionId)
        revisions.remove(sessionId)
        _cards.value = _cards.value.filterNot { it.sessionId == sessionId }
    }

    fun focus(key: String, focused: Boolean) = change(key) { it.copy(focused = focused) }
    fun hold(key: String) = change(key) { it.copy(held = true) }
    fun hide(key: String, hidden: Boolean) = change(key) { it.copy(hidden = hidden) }

    fun requested(sessionId: String, eventId: String, request: AskUserQuestionRequestEvent) {
        val wait = request.wait ?: return
        val clientId = generation() ?: return
        val requestApi = api() ?: return
        val requestMux = mux()
        val key = "$sessionId/${wait.callId}"
        fun updateClaim(transform: (QuestionCardState) -> QuestionCardState) = lock.withLock {
            val current = requests[key]
            if (current?.eventId == eventId && current.generation == clientId) change(key, transform)
        }
        lock.withLock {
            requests.remove(key)?.job?.cancel()
            val old = _cards.value.firstOrNull { it.key == key }
            val card = (old ?: QuestionCardState(sessionId, wait.callId, request.questions))
                .copy(state = "open", ready = !wait.timed, queued = false, answers = null, needsAction = true)
            _cards.value = _cards.value.filterNot { it.key == key } + card
            val job = if (!wait.timed) null else scope.launch(start = CoroutineStart.LAZY) {
                var stream: RemoteStream? = null
                try {
                    val claim = requestMux?.open("userQuestions/attachWait", buildJsonObject {
                        put("agentId", sessionId); put("callId", wait.callId)
                    }) ?: error("Disconnected")
                    stream = claim
                    val opening = claim.receive() ?: return@launch
                    val duration = WireJson.decodeFromJsonElement(QuestionWaitFrame.serializer(), opening).remainingMs
                    onCapability(clientId, RpcResult.Ok(Unit))
                    updateClaim { it.copy(remainingMs = duration, ready = true) }
                    coroutineScope {
                        val countdown = launch {
                            var last = clockMs()
                            while (isActive) {
                                delay(100)
                                val now = clockMs()
                                val elapsed = (now - last).coerceAtLeast(0)
                                last = now
                                updateClaim { card ->
                                    if (card.held || card.focused || !card.ready) card else
                                        card.copy(remainingMs = card.remainingMs?.let { (it - elapsed).coerceAtLeast(0) })
                                }
                                val current = cards.value.firstOrNull { it.key == key } ?: break
                                if (current.remainingMs == 0L && !current.held && !current.focused) {
                                    requestApi.answerEvent(clientId, eventId, RemoteEventOutcome.Rejected(
                                        error = RemoteEventRejection("UserQuestionError", "The foreground answer window ended", "ASK_TIMED_OUT"),
                                    ))
                                    updateClaim { if (it.state == "open") it.copy(ready = false, remainingMs = null) else it }
                                    // Releasing the claim also lets the host enforce its own deadline
                                    // if the HTTP rejection receipt was lost.
                                    claim.cancel()
                                    break
                                }
                            }
                        }
                        try { while (claim.receive() != null) { /* claim ends when the host settles */ } }
                        finally { countdown.cancel() }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val failure = (e as? RemoteStreamException)?.error?.classifyCapability()
                        ?: RpcError("internal", e.message ?: e.toString())
                    onCapability(clientId, RpcResult.Err(failure))
                    updateClaim { it.copy(ready = false, remainingMs = null) }
                    if (clientId == generation() && failure.code == "capability-unavailable") {
                        updateClaim { it.copy(needsAction = false) }
                        onUnavailable(sessionId, eventId, request.questions)
                    }
                }
                finally {
                    stream?.cancel()
                    lock.withLock {
                        if (requests[key]?.eventId == eventId) updateClaim {
                            if (it.state == "open") it.copy(ready = false, remainingMs = null) else it
                        }
                    }
                }
            }
            requests[key] = Request(eventId, clientId, job)
            job?.start()
        }
    }

    fun cancelled(eventId: String) = lock.withLock {
        val entry = requests.entries.firstOrNull { it.value.eventId == eventId } ?: return@withLock
        requests.remove(entry.key)?.job?.cancel()
        change(entry.key) { it.copy(ready = false, remainingMs = null, needsAction = false) }
    }

    fun projection(sessionId: String, seq: Int, value: JsonElement) {
        val view = runCatching { WireJson.decodeFromJsonElement(UserQuestionsView.serializer(), value) }.getOrNull() ?: return
        lock.withLock {
            if (seq < (versions[sessionId] ?: -1)) return
            versions[sessionId] = seq
            revisions[sessionId] = ++revision
            val previous = _cards.value.filter { it.sessionId == sessionId }.associateBy { it.callId }
            val next = view.active.map { question ->
                val old = previous[question.callId]
                val key = "$sessionId/${question.callId}"
                if (question.state == "continued") requests.remove(key)?.job?.cancel()
                (old ?: QuestionCardState(sessionId, question.callId, question.questions)).copy(
                    state = question.state,
                    ready = question.state == "continued" || old?.ready == true,
                    remainingMs = if (question.state == "continued") null else old?.remainingMs,
                )
            }.toMutableList()
            view.settled.forEach { settled ->
                previous[settled.callId]?.let { old ->
                    requests.remove(old.key)?.job?.cancel()
                    next += old.copy(state = "settled", answers = settled.answers, ready = false, queued = false, remainingMs = null, hidden = old.hidden || old.state != "settled")
                }
            }
            // A forwarded request may arrive ahead of the projection that first records it.
            previous.values.filter { it.callId !in next.map(QuestionCardState::callId) && requests.containsKey(it.key) }
                .forEach { next += it }
            _cards.value = _cards.value.filterNot { it.sessionId == sessionId } + next
        }
    }

    fun inbox(sessionId: String, value: JsonElement) {
        val obj = value as? JsonObject ?: return
        val replies = listOf("next-step", "next-turn").flatMap { (obj[it] as? JsonArray).orEmpty() }
            .mapNotNull { row -> (row as? JsonObject)?.get("source") as? JsonObject }
            .filter { it["kind"]?.jsonPrimitive?.contentOrNull == "user-question-reply" }
            .mapNotNull { it["callId"]?.jsonPrimitive?.contentOrNull }.toSet()
        lock.withLock {
            revisions[sessionId] = ++revision
            _cards.value = _cards.value.map { if (it.sessionId == sessionId) it.copy(queued = it.state == "continued" && it.callId in replies) else it }
        }
    }

    fun restoreSettled(snapshot: com.labteto.dshmobile.core.session.ConversationSnapshot) {
        val raw = snapshot.projections["userQuestions"] ?: return
        val view = runCatching { WireJson.decodeFromJsonElement(UserQuestionsView.serializer(), raw) }.getOrNull() ?: return
        lock.withLock {
            val existing = _cards.value.map { it.key }.toSet()
            val calls = snapshot.nodes.filterIsInstance<com.labteto.dshmobile.core.session.ToolCallNode>().associateBy { it.callId }
            val restored = view.settled.mapNotNull { settled ->
                if ("${snapshot.sessionId}/${settled.callId}" in existing) return@mapNotNull null
                val call = calls[settled.callId] ?: return@mapNotNull null
                val request = runCatching { WireJson.decodeFromString(AskUserQuestionRequestEvent.serializer(), call.arguments) }.getOrNull() ?: return@mapNotNull null
                QuestionCardState(snapshot.sessionId, settled.callId, request.questions, state = "settled", answers = settled.answers, hidden = true)
            }
            _cards.value = _cards.value + restored
        }
    }

    suspend fun answer(key: String, answer: AskUserQuestionAnswer): String? {
        val (answeringEpoch, card, answeringRevision) = lock.withLock {
            val card = _cards.value.firstOrNull { it.key == key } ?: return null
            Triple(epoch, card, revisions[card.sessionId])
        }
        val client = api() ?: return "Disconnected"
        if (card.state == "continued") {
            val clientId = generation()
            val result = client.answerContinuedQuestion(card.sessionId, card.callId, answer)
            onCapability(clientId, result)
            return when (result) {
                is RpcResult.Err -> result.error.message
                is RpcResult.Ok -> {
                    lock.withLock {
                        if (answeringEpoch == epoch && answeringRevision == revisions[card.sessionId]) {
                            change(key) {
                                if (it.state != "continued") it
                                else if (result.value) it.copy(queued = true, answers = answer.answers)
                                else it.copy(ready = false)
                            }
                        }
                    }
                    null
                }
            }
        }
        val request = lock.withLock { requests[key] } ?: return "Disconnected"
        if (request.generation != generation()) return "Disconnected"
        return when (val result = client.answerEvent(request.generation, request.eventId,
            RemoteEventOutcome.Result(value = WireJson.encodeToJsonElement(AskUserQuestionAnswer.serializer(), answer)))) {
            is RpcResult.Err -> result.error.message
            is RpcResult.Ok -> {
                // Sending an event result is not evidence that we won another client's answer.
                lock.withLock {
                    if (epoch == answeringEpoch && requests[key] == request) change(key) {
                        if (it.state == "open") it.copy(ready = false, needsAction = false) else it
                    }
                }
                null
            }
        }
    }
}
