package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmDtosTest {
    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `a 0_2_2 directory row keeps its endpoint and native auth facts`() {
        val rows = decodeFromJsonElement(
            ListSerializer(LlmConfigurableProvider.serializer()),
            parse(
                """
                [{"provider":"openai","displayName":"OpenAI","settingsNs":"llm-pi-ai","settingsPath":["providers","openai"],
                  "active":false,"declared":false,"defaultBaseURL":"https://api.openai.com/v1",
                  "baseURLEnv":["OPENAI_BASE_URL"],"nativeAuth":{"env":["OPENAI_API_KEY"],"oauth":false},
                  "error":"model gpt-x has no context window","futureField":{"x":1}}]
                """,
            ),
        )
        val row = rows.single()
        assertEquals("https://api.openai.com/v1", row.defaultBaseURL)
        assertEquals(listOf("OPENAI_BASE_URL"), row.baseURLEnv)
        assertEquals(LlmNativeAuth(env = listOf("OPENAI_API_KEY"), oauth = false), row.nativeAuth)
        assertEquals("model gpt-x has no context window", row.error)
        assertEquals(listOf("providers", "openai"), row.settingsPath)
    }

    @Test
    fun `a baseline directory row without the new fields still decodes`() {
        val row = decodeFromJsonElement(
            LlmConfigurableProvider.serializer(),
            parse("""{"provider":"deepseek-official","displayName":"DeepSeek","settingsNs":"llm-deepseek","settingsPath":[]}"""),
        )
        assertFalse(row.active)
        assertNull(row.defaultBaseURL)
        assertNull(row.baseURLEnv)
        assertNull(row.nativeAuth)
        assertNull(row.error)
    }

    @Test
    fun `native auth tolerates a missing env list`() {
        val auth = decodeFromJsonElement(LlmNativeAuth.serializer(), parse("""{"oauth":true}"""))
        assertTrue(auth.oauth)
        assertEquals(emptyList<String>(), auth.env)
    }

    @Test
    fun `credential describe answers a map that never carries the value`() {
        val map = decodeFromJsonElement(
            MapSerializer(String.serializer(), CredentialInfo.serializer()),
            parse("""{"DEEPSEEK_API_KEY":{"configured":true,"source":"credentials-local","writable":true,"extra":1},"X_API_KEY":{}}"""),
        )
        assertEquals(CredentialInfo(configured = true, source = "credentials-local", writable = true), map["DEEPSEEK_API_KEY"])
        assertEquals(CredentialInfo(), map["X_API_KEY"])
    }
}
