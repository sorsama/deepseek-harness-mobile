package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import org.junit.Assert.*
import org.junit.Test

class FeatureAvailabilityTest {
    private class FailingTransport(val status: Int) : RpcTransport {
        override suspend fun post(path: String, body: String): RpcHttpResponse =
            throw RpcTransportException(status, "failure $status")
        override suspend fun <T> download(path: String, consume: (String?, String?, Source) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: Source, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
    }

    private suspend fun probe(api: DshApiClient, feature: HarnessFeature): RpcResult<*> = when (feature) {
        HarnessFeature.AUTOMATION -> api.automationCatalog()
        HarnessFeature.PLUGIN_MANAGEMENT -> api.pluginBundles()
        HarnessFeature.SESSION_REFERENCES -> api.sessionReferenceCandidates("s", "")
        HarnessFeature.TIMED_QUESTIONS -> api.answerContinuedQuestion("s", "call", AskUserQuestionAnswer(emptyList()))
        HarnessFeature.PRESET_COMPOSITION -> api.agentPresetRead("standard")
        HarnessFeature.PLUGIN_MARKET -> api.marketIndexes()
        HarnessFeature.HARNESS_SETTINGS -> api.settingsDescribe()
        HarnessFeature.ACCOUNT -> api.accountState()
    }

    @Test fun `404 endpoints retire each feature without reporting a connection failure`() = runTest {
        for (status in listOf(404)) {
            val errors = mutableListOf<RpcError>()
            val availability = FeatureAvailability { errors += it }
            for (feature in HarnessFeature.entries) {
                availability.observe(feature, availability.generation(), probe(DshApiClient(FailingTransport(status)), feature))
                assertEquals(false, availability.state(feature).value)
                // A concurrent successful read cannot restore a family with a missing method.
                availability.observe(feature, availability.generation(), RpcResult.Ok(Unit))
                assertEquals(false, availability.state(feature).value)
            }
            assertTrue(errors.isEmpty())
        }
    }

    @Test fun `authentication transport timeout and server errors stay visible and report their original error`() = runTest {
        for (status in listOf(401, 403, 0, 408, 500, 503)) {
            for (feature in HarnessFeature.entries) {
                val errors = mutableListOf<RpcError>()
                val availability = FeatureAvailability { errors += it }
                val result = probe(DshApiClient(FailingTransport(status)), feature) as RpcResult.Err
                availability.observe(feature, availability.generation(), result)
                assertNull(availability.state(feature).value)
                assertEquals(listOf(result.error), errors)
                availability.observe(feature, availability.generation(), RpcResult.Ok(Unit))
                availability.observe(feature, availability.generation(), result)
                assertEquals(true, availability.state(feature).value)
            }
        }
    }

    @Test fun `an answer to the question itself brings a retired feature back and a retired one goes again`() {
        // The market is an optional bundle: switched on after the first probe said it was missing, switched off again later.
        val availability = FeatureAvailability { fail("No error is expected") }
        val unavailable = RpcResult.Err(RpcError("capability-unavailable", "missing"))
        val market = HarnessFeature.PLUGIN_MARKET
        availability.observe(market, availability.generation(), unavailable)
        assertEquals(false, availability.state(market).value)
        // An ordinary success (another call racing the first) still cannot restore it...
        availability.observe(market, availability.generation(), RpcResult.Ok(Unit))
        assertEquals(false, availability.state(market).value)
        // ...but the re-probe's own success can.
        availability.observe(market, availability.generation(), RpcResult.Ok(Unit), authoritative = true)
        assertEquals(true, availability.state(market).value)
        // And the same re-probe, after the bundle was turned off, retires it.
        availability.observe(market, availability.generation(), unavailable, authoritative = true)
        assertEquals(false, availability.state(market).value)
    }

    @Test fun `an authoritative answer from an old connection is ignored`() {
        val availability = FeatureAvailability { fail("No error is expected") }
        val old = availability.generation()
        availability.observe(HarnessFeature.PLUGIN_MARKET, old, RpcResult.Err(RpcError("capability-unavailable", "missing")))
        availability.reset()
        availability.observe(HarnessFeature.PLUGIN_MARKET, old, RpcResult.Ok(Unit), authoritative = true)
        assertNull(availability.state(HarnessFeature.PLUGIN_MARKET).value)
    }

    @Test fun `HTTP 403 remains visible for every feature`() = runTest {
        for (feature in HarnessFeature.entries) {
            val errors = mutableListOf<RpcError>()
            val availability = FeatureAvailability { errors += it }
            availability.observe(feature, availability.generation(), probe(DshApiClient(FailingTransport(403)), feature))
            assertNull(availability.state(feature).value)
            assertEquals("forbidden", errors.single().code)
        }
    }

    @Test fun `both gateway missing method codes retire features without suppressing unrelated errors`() {
        for (code in listOf("gateway/invocation-unavailable", "gateway/definition-unavailable")) {
            val availability = FeatureAvailability { fail("Missing method must not report an auth error") }
            for (feature in HarnessFeature.entries) {
                availability.observe(feature, availability.generation(), RpcResult.Err(RpcError(code, "missing")))
                assertEquals(false, availability.state(feature).value)
            }
        }
    }

    @Test fun `reconnect resets every feature and ignores late results from the old connection`() {
        val errors = mutableListOf<RpcError>()
        val availability = FeatureAvailability { errors += it }
        val old = availability.generation()
        val unavailable = RpcResult.Err(RpcError("capability-unavailable", "missing"))
        HarnessFeature.entries.forEach { availability.observe(it, old, unavailable) }
        availability.reset()
        HarnessFeature.entries.forEach {
            assertNull(availability.state(it).value)
            availability.observe(it, old, unavailable)
            availability.observe(it, old, RpcResult.Err(RpcError("unauthenticated", "old auth")))
            assertNull(availability.state(it).value)
            availability.observe(it, availability.generation(), RpcResult.Ok(Unit))
            assertEquals(true, availability.state(it).value)
        }
        assertTrue(errors.isEmpty())
    }

    private val missing = RpcResult.Err(RpcError("capability-unavailable", "missing"))

    @Test fun `a module change gives an unavailable feature another chance`() {
        // The harness lacked settings, then a bundle that brings them was switched on in the app.
        val availability = FeatureAvailability { fail("no error to report") }
        availability.observe(HarnessFeature.HARNESS_SETTINGS, availability.generation(), missing)
        assertEquals(false, availability.state(HarnessFeature.HARNESS_SETTINGS).value)

        val lapsed = availability.relax(setOf(HarnessFeature.HARNESS_SETTINGS, HarnessFeature.AUTOMATION))
        assertEquals(listOf(HarnessFeature.HARNESS_SETTINGS), lapsed)
        // Unknown again, so a probe can settle it, and a later answer is believed.
        assertNull(availability.state(HarnessFeature.HARNESS_SETTINGS).value)
        availability.observe(HarnessFeature.HARNESS_SETTINGS, availability.generation(), RpcResult.Ok(Unit))
        assertEquals(true, availability.state(HarnessFeature.HARNESS_SETTINGS).value)
    }

    @Test fun `a module change leaves available and unknown features alone and asks for nothing`() {
        val availability = FeatureAvailability { fail("no error to report") }
        availability.observe(HarnessFeature.AUTOMATION, availability.generation(), RpcResult.Ok(Unit))
        val before = availability.generation()
        assertEquals(emptyList<HarnessFeature>(), availability.relax(HarnessFeature.entries.toSet()))
        assertEquals(true, availability.state(HarnessFeature.AUTOMATION).value)
        assertNull(availability.state(HarnessFeature.HARNESS_SETTINGS).value)
        // Nothing lapsed, so nothing in flight was invalidated either.
        assertEquals(before, availability.generation())
    }

    @Test fun `only the features that have a probe come back from a module change`() {
        val availability = FeatureAvailability { fail("no error to report") }
        HarnessFeature.entries.forEach { availability.observe(it, availability.generation(), missing) }
        val lapsed = availability.relax(setOf(HarnessFeature.HARNESS_SETTINGS, HarnessFeature.PLUGIN_MANAGEMENT))
        assertEquals(setOf(HarnessFeature.HARNESS_SETTINGS, HarnessFeature.PLUGIN_MANAGEMENT), lapsed.toSet())
        // A feature nobody can ask about again keeps its answer rather than turning into a guess.
        assertEquals(false, availability.state(HarnessFeature.TIMED_QUESTIONS).value)
        assertEquals(false, availability.state(HarnessFeature.SESSION_REFERENCES).value)
    }

    @Test fun `a result still in flight from before a module change is dropped`() {
        val availability = FeatureAvailability { fail("no error to report") }
        availability.observe(HarnessFeature.HARNESS_SETTINGS, availability.generation(), missing)
        val stale = availability.generation()
        availability.relax(setOf(HarnessFeature.HARNESS_SETTINGS))
        // The old probe answers "missing" after the bundle was already switched on.
        availability.observe(HarnessFeature.HARNESS_SETTINGS, stale, missing)
        assertNull(availability.state(HarnessFeature.HARNESS_SETTINGS).value)
    }
}
