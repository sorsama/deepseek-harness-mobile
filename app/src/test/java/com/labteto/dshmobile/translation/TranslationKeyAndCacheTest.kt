package com.labteto.dshmobile.translation

import com.labteto.dshmobile.core.wire.TranslationLimits
import com.labteto.dshmobile.core.wire.TranslationPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslationKeyAndCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private fun key(
        host: String = "http://h|1", session: String = "s1", block: String = "turn-1/step-1#0",
        target: String = "zh-CN", provider: String = "deepseek-account", text: String = "hello",
    ) = TranslationKey.of(host, session, block, target, provider, text)

    // ---- the key -------------------------------------------------------------------------------------

    @Test fun `the same block built twice has the same id`() {
        assertEquals(key().id, key().id)
        assertEquals(key(), key())
    }

    @Test fun `every part of the key changes the id`() {
        val base = key().id
        listOf(
            key(host = "http://other|1"), key(session = "s2"), key(block = "turn-1/step-1#1"),
            key(target = "ar"), key(provider = "bing"), key(text = "hello!"),
        ).forEach { assertNotEquals(base, it.id) }
    }

    @Test fun `text of the same length but different content is a different key`() {
        assertNotEquals(textFingerprint("abcd"), textFingerprint("abce"))
        assertNotEquals(key(text = "abcd").id, key(text = "abce").id)
    }

    @Test fun `parts cannot run together into the same id`() {
        assertNotEquals(key(session = "ab", block = "c").id, key(session = "a", block = "bc").id)
    }

    @Test fun `the language is compared without case`() {
        assertEquals(key(target = "zh-CN").id, key(target = "zh-cn").id)
    }

    @Test fun `the id reveals no host address or session`() {
        val id = key(host = "http://192.168.1.5:3080|abc", session = "secret-session").id
        assertFalse(id.contains("192"))
        assertFalse(id.contains("secret"))
        assertEquals(64, id.length)
    }

    @Test fun `the target is the host preference, or the app language when it says auto`() {
        assertEquals("ja", resolveTargetLanguage("ja", "zh-CN"))
        assertEquals("zh-CN", resolveTargetLanguage("auto", "zh-CN"))
    }

    @Test fun `translation is available only with a usable provider and a real limit`() {
        fun limits(max: Int, provider: String, all: List<String>) =
            TranslationLimits(max, TranslationPreferences(provider, "auto"), all)
        assertTrue(limits(4000, "bing", listOf("bing", "google")).canTranslate())
        assertFalse(limits(4000, "deepseek-account", listOf("bing")).canTranslate())
        assertFalse(limits(1, "bing", listOf("bing")).canTranslate())
        assertFalse(limits(4000, "bing", emptyList()).canTranslate())
    }

    // ---- the cache ------------------------------------------------------------------------------------

    @Test fun `the entry used longest ago goes first when there are too many`() {
        val cache = TranslationCache(maxEntries = 3, maxChars = 1_000)
        cache.put("a", "1"); cache.put("b", "2"); cache.put("c", "3")
        cache.get("a") // a is in use again, so b is now the oldest
        cache.put("d", "4")
        assertNull(cache.get("b"))
        assertEquals(listOf("c", "a", "d"), cache.snapshot().map { it.id })
    }

    @Test fun `the cache is capped by characters too`() {
        val cache = TranslationCache(maxEntries = 100, maxChars = 10)
        cache.put("a", "12345"); cache.put("b", "12345"); cache.put("c", "12345")
        assertNull(cache.get("a"))
        assertEquals(10, cache.totalChars())
        assertEquals(2, cache.size())
    }

    @Test fun `one translation bigger than the cap is kept until the next one comes`() {
        val cache = TranslationCache(maxEntries = 100, maxChars = 10)
        cache.put("big", "x".repeat(50))
        assertEquals("x".repeat(50), cache.get("big"))
        cache.put("small", "y")
        assertNull(cache.get("big"))
        assertEquals("y", cache.get("small"))
    }

    @Test fun `replacing an entry does not count its old text twice`() {
        val cache = TranslationCache(maxEntries = 100, maxChars = 10)
        cache.put("a", "12345678"); cache.put("a", "1234")
        assertEquals(4, cache.totalChars())
    }

    @Test fun `restoring puts saved entries behind the live ones and never over them`() {
        val cache = TranslationCache(maxEntries = 3, maxChars = 1_000)
        cache.put("live", "new")
        cache.restore(listOf(CachedTranslation("live", "old"), CachedTranslation("x", "1"), CachedTranslation("y", "2"), CachedTranslation("z", "3")))
        assertEquals("new", cache.get("live"))
        assertEquals(3, cache.size())
        assertNull(cache.get("x")) // oldest saved entry gave way
    }

    // ---- the file ---------------------------------------------------------------------------------------

    @Test fun `entries written to the file read back in order`() {
        val storage = FileTranslationStorage(folder.root.resolve("t.json"))
        val entries = listOf(CachedTranslation("a", "你好"), CachedTranslation("b", "مرحبا \"quoted\"\nline"))
        storage.write(entries)
        assertEquals(entries, storage.read())
    }

    @Test fun `a missing, damaged or other-version file reads as empty`() {
        val file = folder.root.resolve("t.json")
        val storage = FileTranslationStorage(file)
        assertEquals(emptyList<CachedTranslation>(), storage.read())
        file.writeText("{ not json")
        assertEquals(emptyList<CachedTranslation>(), storage.read())
        file.writeText("""{"version":99,"entries":[{"id":"a","text":"b"}]}""")
        assertEquals(emptyList<CachedTranslation>(), storage.read())
    }

    @Test fun `a file bigger than the caps is trimmed to them on load`() {
        val cache = TranslationCache(maxEntries = 2, maxChars = 1_000)
        cache.restore((1..5).map { CachedTranslation("id$it", "t$it") })
        assertEquals(listOf("id4", "id5"), cache.snapshot().map { it.id })
    }
}
