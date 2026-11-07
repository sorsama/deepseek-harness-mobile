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
}
