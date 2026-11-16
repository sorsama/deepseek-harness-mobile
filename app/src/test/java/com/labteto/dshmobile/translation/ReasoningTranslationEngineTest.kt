package com.labteto.dshmobile.translation

import com.labteto.dshmobile.core.wire.TranslationLimits
import com.labteto.dshmobile.core.wire.TranslationPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReasoningTranslationEngineTest {
    private fun key(block: String = "turn-1/step-1#0", provider: String = "deepseek-account", text: String = "hello", target: String = "zh-CN", session: String = "s1") =
        TranslationKey.of("http://h|1", session, block, target, provider, text)

    private class Fetches {
        var started = 0
        var finished = 0
        var inFlight = 0
        var peak = 0
        val cancelled = mutableListOf<Int>()

        fun counting(latencyMs: Long = 100, result: String = "译文"): suspend () -> String = {
            val n = ++started
            inFlight++
            peak = maxOf(peak, inFlight)
            try {
                delay(latencyMs)
                finished++
                result
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += n
                throw e
            } finally {
                inFlight--
            }
        }
    }

    /** An engine whose work all runs on the test's virtual clock, with no file. */
    private fun TestScope.engine(
        maxConcurrent: Int = MAX_CONCURRENT_TRANSLATIONS,
        storage: TranslationStorage? = null,
        cache: TranslationCache = TranslationCache(),
        clock: () -> Long = { currentTime },
    ) = ReasoningTranslationEngine(
        parentScope = backgroundScope,
        storage = storage,
        io = StandardTestDispatcher(testScheduler),
        maxConcurrent = maxConcurrent,
        cache = cache,
        clock = clock,
    )

    // ---- dedup: one key is translated once -------------------------------------------------

    @Test fun `a key is translated once and then answered from the cache`() = runTest {
        val engine = engine()
        val fetches = Fetches()
        assertEquals("译文", engine.translate(key(), fetches.counting()))
        assertEquals("译文", engine.translate(key(), fetches.counting()))
        assertEquals("译文", engine.peek(key()))
        assertEquals(1, fetches.started)
    }

    @Test fun `two askers for one key while it runs share one request`() = runTest {
        val engine = engine()
        val fetches = Fetches()
        val a = async { engine.translate(key(), fetches.counting()) }
        val b = async { engine.translate(key(), fetches.counting()) }
        advanceUntilIdle()
        assertEquals("译文", a.await())
        assertEquals("译文", b.await())
        assertEquals(1, fetches.started)
    }

    @Test fun `manual and automatic translation share the cache both ways`() = runTest {
        val engine = engine()
        val fetches = Fetches()
        // Automatic first: a manual ask afterwards is a cache hit.
        engine.translateAfterRest(key("a"), 700, fetch = fetches.counting())
        assertEquals("译文", engine.translate(key("a"), fetches.counting()))
        assertEquals(1, fetches.started)
        // Manual first: the automatic pass finds it and neither waits nor asks.
        engine.translate(key("b"), fetches.counting())
        val before = currentTime
        var requested = false
        assertEquals("译文", engine.translateAfterRest(key("b"), 700, onRequest = { requested = true }, fetch = fetches.counting()))
        assertEquals(before, currentTime)
        assertFalse(requested)
        assertEquals(2, fetches.started)
    }

    @Test fun `history replay and a reconnect ask again with the same key and send nothing`() = runTest {
        val engine = engine()
        val fetches = Fetches()
        // The block's identity is the message's state key and the block index: both survive a replay.
        engine.translate(key(block = "turn-4/step-2#1"), fetches.counting())
        // Reopened after a reconnect: a new composition, a new key object, the same identity.
        val replayed = key(block = "turn-4/step-2#1")
        assertEquals("译文", engine.translateAfterRest(replayed, 700, fetch = fetches.counting()))
        assertEquals(1, fetches.started)
    }

    @Test fun `a changed text, provider, language or session is a different translation`() = runTest {
        val engine = engine()
        val fetches = Fetches()
        engine.translate(key(), fetches.counting())
        engine.translate(key(text = "hello!"), fetches.counting())
        engine.translate(key(provider = "bing"), fetches.counting())
        engine.translate(key(target = "ar"), fetches.counting())
        engine.translate(key(session = "s2"), fetches.counting())
        assertEquals(5, fetches.started)
    }

    @Test fun `translations survive a restart through the storage file`() = runTest {
        val storage = MemoryStorage()
        val first = engine(storage = storage)
        val fetches = Fetches()
        first.translate(key(), fetches.counting(result = "你好"))
        advanceUntilIdle()
        // The write is debounced; it has happened by now, and flush() is the way to force it.
        first.flush()
        assertEquals(1, storage.saved.size)

        val second = engine(storage = storage)
        second.awaitReady()
        assertEquals("你好", second.peek(key()))
        assertEquals("你好", second.translate(key(), fetches.counting()))
        assertEquals(1, fetches.started)
    }

    // ---- only what is on screen, and not at once ------------------------------------------------

    @Test fun `a block that leaves the screen before it rests is never requested`() = runTest {
        val engine = engine()
        val fetches = Fetches()
        // Ten blocks flung past: each is composed for a moment and gone.
        val jobs = (0 until 10).map { i ->
            launch { engine.translateAfterRest(key("b$i"), 700, fetch = fetches.counting()) }
        }
        advanceTimeBy(300)
        jobs.forEach { it.cancel() }
        advanceUntilIdle()
        assertEquals(0, fetches.started)
        assertEquals(0, engine.pending())
    }

    @Test fun `a block that rests on screen is requested once the rest is over`() = runTest {
        val engine = engine()
        val fetches = Fetches()
        var loading = false
        val job = launch { engine.translateAfterRest(key(), 700, onRequest = { loading = true }, fetch = fetches.counting()) }
        advanceTimeBy(699)
        assertEquals(0, fetches.started)
        assertFalse(loading)
        advanceTimeBy(2)
        assertTrue(loading)
        assertEquals(1, fetches.started)
        advanceUntilIdle()
        job.join()
        assertEquals("译文", engine.peek(key()))
    }

    @Test fun `at most the limit run at once and the rest wait their turn`() = runTest {
        val engine = engine(maxConcurrent = 2)
        val fetches = Fetches()
        val jobs = (0 until 7).map { i -> async { engine.translate(key("b$i"), fetches.counting(latencyMs = 100)) } }
        advanceTimeBy(1)
        assertEquals(2, fetches.inFlight)
        advanceUntilIdle()
        jobs.forEach { assertEquals("译文", it.await()) }
        assertEquals(7, fetches.finished)
        assertEquals(2, fetches.peak)
    }

    @Test fun `the default ceiling is two`() {
        assertEquals(2, MAX_CONCURRENT_TRANSLATIONS)
    }

    // ---- failures are not retried on their own ----------------------------------------------------

    @Test fun `a failed block stays failed and automatic translation does not try it again`() = runTest {
        val engine = engine()
        var calls = 0
        val failing: suspend () -> String = { calls++; throw java.io.IOException("provider down") }
        try {
            engine.translateAfterRest(key(), 700, fetch = failing)
            fail("expected the failure")
        } catch (e: java.io.IOException) {
            assertEquals("provider down", e.message)
        }
        assertTrue(engine.hasFailed(key()))
        assertNull(engine.peek(key()))

        // The block scrolls away and back, or the setting is toggled: still no request, and no wait.
        val before = currentTime
        assertNull(engine.translateAfterRest(key(), 700, fetch = failing))
        assertEquals(before, currentTime)
        assertEquals(1, calls)
    }

    @Test fun `asking again by hand is the retry and clears the failure`() = runTest {
        val engine = engine()
        var calls = 0
        val flaky: suspend () -> String = { if (calls++ == 0) throw java.io.IOException("once") else "好了" }
        try { engine.translate(key(), flaky); fail() } catch (_: java.io.IOException) {}
        assertTrue(engine.hasFailed(key()))
        assertEquals("好了", engine.translate(key(), flaky))
        assertFalse(engine.hasFailed(key()))
        assertEquals(2, calls)
    }

    @Test fun `an empty answer counts as a failure and is not cached`() = runTest {
        val engine = engine()
        try { engine.translate(key()) { "   " }; fail() } catch (_: IllegalStateException) {}
        assertTrue(engine.hasFailed(key()))
        assertNull(engine.peek(key()))
    }

    @Test fun `a failing request does not take the engine down`() = runTest {
        val engine = engine()
        try { engine.translate(key("bad")) { throw IllegalStateException("x") }; fail() } catch (_: IllegalStateException) {}
        assertEquals("好", engine.translate(key("good")) { "好" })
    }

    // ---- cancellation ----------------------------------------------------------------------------

    /** advanceUntilIdle() stops once only background work is left, and the engine's requests are background work. */
    private fun TestScope.settle() { advanceUntilIdle(); runCurrent() }

    @Test fun `leaving the screen cancels a running request and caches nothing`() = runTest {
        val engine = engine()
        val fetches = Fetches()
        val job = launch { engine.translateAfterRest(key(), 700, fetch = fetches.counting(latencyMs = 5_000)) }
        advanceTimeBy(1_000)
        assertEquals(1, fetches.inFlight)
        job.cancel()
        settle()
        assertEquals(listOf(1), fetches.cancelled)
        assertEquals(0, fetches.inFlight)
        assertEquals(0, engine.pending())
        assertNull(engine.peek(key()))
        // Cancelled is not failed: the next time the block is on screen it is requested.
        assertFalse(engine.hasFailed(key()))
    }

    @Test fun `turning the switch off is a cancel and a finished translation stays cached`() = runTest {
        val engine = engine()
        val fetches = Fetches()
        // One block finished before the switch went off, one was still running.
        engine.translate(key("done"), fetches.counting(latencyMs = 10))
        val running = launch { engine.translate(key("running"), fetches.counting(latencyMs = 5_000)) }
        advanceTimeBy(500)
        running.cancel()
        settle()
        assertEquals("译文", engine.peek(key("done")))
        assertNull(engine.peek(key("running")))
        assertEquals(1, fetches.cancelled.size)
    }

    @Test fun `a request one asker leaves keeps running for the other and stops when both are gone`() = runTest {
        val engine = engine()
        val fetches = Fetches()
        val first = launch { engine.translate(key(), fetches.counting(latencyMs = 1_000)) }
        val second = async { engine.translate(key(), fetches.counting(latencyMs = 1_000)) }
        advanceTimeBy(100)
        first.cancel()
        settle()
        assertEquals("译文", second.await())
        assertEquals(1, fetches.started)
        assertTrue(fetches.cancelled.isEmpty())

        // And with every asker gone, the request goes too.
        val a = launch { engine.translate(key("x"), fetches.counting(latencyMs = 1_000)) }
        val b = launch { engine.translate(key("x"), fetches.counting(latencyMs = 1_000)) }
        advanceTimeBy(100)
        a.cancel(); b.cancel()
        settle()
        assertEquals(1, fetches.cancelled.size)
        assertEquals(0, engine.pending())
    }

    @Test fun `a request cancelled while it waits for a slot never starts`() = runTest {
        val engine = engine(maxConcurrent = 1)
        val fetches = Fetches()
        val gate = CompletableDeferred<Unit>()
        val holder = launch { engine.translate(key("holder")) { gate.await(); "x" } }
        val waiting = launch { engine.translate(key("waiting"), fetches.counting()) }
        advanceTimeBy(50)
        waiting.cancel()
        gate.complete(Unit)
        settle()
        holder.join()
        assertEquals(0, fetches.started)
    }

    // ---- limits: one probe for many blocks ---------------------------------------------------------

    @Test fun `limits are asked once for many blocks and again after the time is up`() = runTest {
        val engine = engine()
        var asked = 0
        val limits = TranslationLimits(4_000, TranslationPreferences("deepseek-account", "auto"), listOf("deepseek-account"))
        repeat(10) { engine.limits("host") { asked++; limits } }
        assertEquals(1, asked)
        advanceTimeBy(31_000)
        engine.limits("host") { asked++; limits }
        assertEquals(2, asked)
    }

    @Test fun `a host with no translator is probed once and not once per block`() = runTest {
        val engine = engine()
        var asked = 0
        repeat(10) { assertNull(engine.limits("bare") { asked++; null }) }
        assertEquals(1, asked)
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private class MemoryStorage : TranslationStorage {
        var saved: List<CachedTranslation> = emptyList()
        override fun read() = saved
        override fun write(entries: List<CachedTranslation>) { saved = entries }
    }
}
