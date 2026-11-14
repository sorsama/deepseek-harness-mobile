package com.labteto.dshmobile.core.wire

import org.junit.Assert.*
import org.junit.Test

class ReasoningTranslationTest {
    @Test fun `fragment limits preserve emoji and the complete original reasoning`() {
        val text = "a😀 reasoning\n中文 😀"
        for (limit in 2..text.length) {
            val fragments = translationFragments(text, limit)
            assertEquals(text, fragments.joinToString(""))
            assertTrue(fragments.all { it.length <= limit && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        }
    }

    // The translate button read "Translate with deepseek-account" (issue #96): the host names a
    // provider by route key only, so the label has to be made here.
    @Test fun `the paid account route reads as DeepSeek, not as its route key`() {
        assertEquals("DeepSeek", translationProviderLabel("deepseek-account"))
    }

    @Test fun `every provider the host can offer has a friendly label`() {
        assertEquals("Bing", translationProviderLabel("bing"))
        assertEquals("Google", translationProviderLabel("google"))
        assertEquals("DeepSeek", translationProviderLabel("deepseek-account"))
        assertEquals("DeepSeek API", translationProviderLabel("deepseek-official"))
    }

    @Test fun `a provider this build has never seen is humanised rather than printed raw`() {
        assertEquals("My Provider", translationProviderLabel("my-provider"))
        assertEquals("Deepl Pro", translationProviderLabel("deepl_pro"))
        assertEquals("DeepSeek Flash", translationProviderLabel("deepseek-flash"))
        assertEquals("OpenAI API", translationProviderLabel("openai-api"))
    }

    @Test fun `odd provider ids do not crash the label`() {
        assertEquals("", translationProviderLabel(""))
        assertEquals("Solo", translationProviderLabel("solo"))
        assertEquals("A B", translationProviderLabel("--a--b--"))
    }
}
