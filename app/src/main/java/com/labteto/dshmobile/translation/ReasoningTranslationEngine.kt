package com.labteto.dshmobile.translation

import com.labteto.dshmobile.core.wire.TranslationLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * How many translation requests are in flight at once, across the whole app.
 *
 * Two. Every request is a call on the reader's own provider and spends their tokens or quota, so the
 * ceiling has to stay low whatever is on screen. One would make a screen with two open thoughts
 * wait on the first one's every fragment; more than two buys nothing a phone screen can show (one or
 * two open thoughts at a time) and is the point where a provider starts answering with rate limits.
 * A thought that is cut into fragments sends them one after another inside its one slot, so a long
 * thought cannot take more than one.
 */
const val MAX_CONCURRENT_TRANSLATIONS = 2

/**
 * How long an open thought has to stay composed before an automatic translation is requested. A
 * block that scrolls past in a fling is composed for a few frames and never gets this long.
 */
const val AUTO_TRANSLATE_REST_MS = 700L

/**
 * The one place reasoning translations are cached, shared, scheduled and limited. Manual and
 * automatic translation both go through [translate], so they share the cache and the limit.
 *
 * It never touches the transcript: it takes a key and a function that fetches the text, and gives
 * text back. The translator's own `plugin:translator/request` and `plugin:translator/result` records are the host's business and are
 * filtered from the chat by the transcript's visibility rules, whoever asked for the translation.
 *
 * What it guarantees:
 *  - A key is translated once. A finished translation is in [cache]; a request that is running is
 *    shared by everyone who asks for the same key while it runs.
 *  - At most [maxConcurrent] fetches run at once; the rest wait their turn.
 *  - Nobody's wait outlives them. A request whose last waiter has gone is cancelled, and nothing
 *    half done is cached. A request that fails is remembered as failed, so [translateAfterRest]
 *    does not try it again on its own; asking with [translate] is the retry.
 *
 * Requests run in [scope], not in the caller, so one caller leaving does not cancel a request
 * another is still waiting on.
 */
class ReasoningTranslationEngine(
    parentScope: CoroutineScope,
    private val storage: TranslationStorage? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val maxConcurrent: Int = MAX_CONCURRENT_TRANSLATIONS,
    private val cache: TranslationCache = TranslationCache(),
    private val persistDelayMs: Long = 1_500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // A request that fails must not take the app's scope down with it.
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private class Flight {
        lateinit var job: Deferred<String>
        var waiters = 0
    }

    private val lock = Any()
    private val flights = HashMap<String, Flight>()
    private val failed = object : LinkedHashMap<String, Unit>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = size > MAX_FAILED_KEPT
    }
    private val permits = Semaphore(maxConcurrent)
    private var persistJob: Job? = null

    /** Completes when the saved cache has been read; nothing is looked up before it. */
    private val loaded: Job = scope.launch(io) {
        val saved = try { storage?.read().orEmpty() } catch (_: Exception) { emptyList() }
        cache.restore(saved)
    }

    /** The finished translation for [key], without waiting for anything. Null while the file is still loading. */
    fun peek(key: TranslationKey): String? = cache.get(key.id)

    /** Waits for the saved cache to load. */
    suspend fun awaitReady() = loaded.join()

    /** Whether the last attempt at [key] failed and nobody has asked again since. */
    fun hasFailed(key: TranslationKey): Boolean = synchronized(lock) { key.id in failed }

    /** How many requests are running or waiting for a slot right now; for tests and diagnostics. */
    fun pending(): Int = synchronized(lock) { flights.size }

    /**
     * The translation of [key]: from the cache, from the request already running for it, or from a
     * new one that calls [fetch] when a slot is free. Calling this is also the retry of a failed key.
     * Throws what [fetch] threw; being cancelled cancels the request if nobody else waits on it.
     */
    suspend fun translate(key: TranslationKey, fetch: suspend () -> String): String {
        awaitReady()
        synchronized(lock) { failed.remove(key.id) }
        cache.get(key.id)?.let { return it }
        val flight = synchronized(lock) {
            flights.getOrPut(key.id) { newFlight(key, fetch) }.also { it.waiters++ }
        }
        flight.job.start()
        try {
            return flight.job.await()
        } finally {
            val stop = synchronized(lock) {
                flight.waiters--
                (flight.waiters == 0 && flight.job.isActive).also { if (it && flights[key.id] === flight) flights.remove(key.id) }
            }
            if (stop) flight.job.cancel()
        }
    }

    /**
     * What automatic translation calls for a block that has been on screen: nothing at all if the
     * block already failed (null, no request), otherwise it waits [restMs] and then behaves like
     * [translate]. Leaving the screen inside that wait cancels it before any request exists.
     *
     * [onRequest] runs once the wait is over and a request, or a wait on one, is about to begin; it
     * is where a loading state belongs. It does not run for a block that is already cached.
     */
    suspend fun translateAfterRest(
        key: TranslationKey,
        restMs: Long = AUTO_TRANSLATE_REST_MS,
        onRequest: () -> Unit = {},
        fetch: suspend () -> String,
    ): String? {
        awaitReady()
        cache.get(key.id)?.let { return it }
        if (hasFailed(key)) return null
        delay(restMs)
        cache.get(key.id)?.let { return it }
        onRequest()
        return translate(key, fetch)
    }

    private fun newFlight(key: TranslationKey, fetch: suspend () -> String): Flight {
        val flight = Flight()
        flight.job = scope.async(start = CoroutineStart.LAZY) {
            try {
                val text = permits.withPermit { fetch() }
                if (text.isBlank()) throw IllegalStateException("The translation came back empty")
                cache.put(key.id, text)
                schedulePersist()
                text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) { failed[key.id] = Unit }
                throw e
            } finally {
                synchronized(lock) { if (flights[key.id] === flight) flights.remove(key.id) }
            }
        }
        return flight
    }

    private fun schedulePersist() {
        val store = storage ?: return
        synchronized(lock) {
            persistJob?.cancel()
            persistJob = scope.launch {
                delay(persistDelayMs)
                val entries = cache.snapshot()
                withContext(io) { store.write(entries) }
            }
        }
    }

    /** Writes the cache now instead of after the delay; used when the app is going away and by tests. */
    suspend fun flush() {
        val store = storage ?: return
        val entries = cache.snapshot()
        withContext(io) { store.write(entries) }
    }

    // ---- what the host says it can do --------------------------------------------------------

    private class LimitsEntry(val value: TranslationLimits?, val at: Long)

    private val limitsLock = Mutex()
    private val limitsByHost = HashMap<String, LimitsEntry>()

    /**
     * What `cotTranslation/limits` said for [hostKey], asked at most once every [LIMITS_TTL_MS]
     * however many blocks and screens want it. Null means the host cannot translate or did not
     * answer, and that is remembered for the same time, so ten open thoughts on a host without a
     * translator send one probe and not ten.
     */
    suspend fun limits(hostKey: String, load: suspend () -> TranslationLimits?): TranslationLimits? =
        limitsLock.withLock {
            val now = clock()
            limitsByHost[hostKey]?.takeIf { now - it.at < LIMITS_TTL_MS }?.let { return it.value }
            val fresh = load()
            limitsByHost[hostKey] = LimitsEntry(fresh, clock())
            fresh
        }

    private companion object {
        const val LIMITS_TTL_MS = 30_000L
        const val MAX_FAILED_KEPT = 200
    }
}
