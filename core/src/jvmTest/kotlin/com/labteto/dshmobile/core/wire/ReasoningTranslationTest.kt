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
}
