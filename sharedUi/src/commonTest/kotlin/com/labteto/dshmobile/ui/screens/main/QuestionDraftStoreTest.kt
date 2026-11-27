package com.labteto.dshmobile.ui.screens.main

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [QuestionDraftStore] over the [QuestionDraftFile] seam (iOS M7): the same entries and the same one-change-per-edit
 * shape it wrote through Android's `SharedPreferences` editor before, a draft's JSON as a String and its save time as
 * a Long beside it.
 */
class QuestionDraftStoreTest {

    /** The file as a map, and every change applied to it, in order. */
    private class MemoryFile(initial: Map<String, Any?> = emptyMap()) : QuestionDraftFile {
        val entries = LinkedHashMap(initial)
        val applied = mutableListOf<Map<String, Any?>>()

        override fun getString(key: String): String? = entries[key] as? String

        override fun contains(key: String): Boolean = key in entries

        override val all: Map<String, *> get() = entries.toMap()

        override fun apply(changes: Map<String, Any?>) {
            applied += changes.toMap()
            changes.forEach { (key, value) -> if (value == null) entries.remove(key) else entries[key] = value }
        }
    }

    private val now = 50L * 24 * 60 * 60 * 1000

    @Test
    fun aSavedDraftIsReadBackWithItsSaveTimeBesideIt() {
        val file = MemoryFile()
        val store = QuestionDraftStore(file) { now }
        store.save("h/s/c", """[{"selected":["A"]}]""")
        assertEquals("""[{"selected":["A"]}]""", store.read("h/s/c"))
        assertEquals(now, file.entries[questionDraftStampKey("h/s/c")])
        assertEquals<List<Map<String, Any?>>>(listOf(mapOf("h/s/c" to """[{"selected":["A"]}]""", questionDraftStampKey("h/s/c") to now)), file.applied)
    }

    @Test
    fun droppingRemovesTheDraftAndItsDateAndNothingWhenThereIsNone() {
        val file = MemoryFile(mapOf("h/s/c" to "[]", questionDraftStampKey("h/s/c") to now))
        val store = QuestionDraftStore(file) { now }
        store.drop("h/s/c")
        assertNull(store.read("h/s/c"))
        assertEquals(emptyMap<String, Any?>(), file.entries.toMap())
        store.drop("h/s/c")
        assertEquals(1, file.applied.size)
    }

    @Test
    fun aSweepDeletesOldOrphansDatesUndatedOnesAndClearsStrayDatesInOneChange() {
        val ttl = QUESTION_DRAFT_TTL_MS
        val file = MemoryFile(
            mapOf(
                "live" to "[]", questionDraftStampKey("live") to now - 2 * ttl,
                "old" to "[]", questionDraftStampKey("old") to now - 2 * ttl,
                "recent" to "[]", questionDraftStampKey("recent") to now - 1,
                "undated" to "[]",
                questionDraftStampKey("gone") to now - 5,
            ),
        )
        QuestionDraftStore(file) { now }.sweep(liveKeys = setOf("live"))
        assertEquals(1, file.applied.size)
        assertEquals<Map<String, Any?>>(
            mapOf(
                "live" to "[]", questionDraftStampKey("live") to now - 2 * ttl,
                "recent" to "[]", questionDraftStampKey("recent") to now - 1,
                "undated" to "[]", questionDraftStampKey("undated") to now,
            ),
            file.entries.toMap(),
        )
    }

    @Test
    fun aSweepWithNothingToDoWritesNothing() {
        val file = MemoryFile(mapOf("recent" to "[]", questionDraftStampKey("recent") to now))
        QuestionDraftStore(file) { now }.sweep(liveKeys = emptySet())
        assertEquals(0, file.applied.size)
    }
}
