package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionDraftsTest {

    private val hour = 60L * 60 * 1000
    private val now = 1_000L * hour

    @Test
    fun `a draft key names its host then its card`() {
        assertEquals("http://h:3080|id/s1/c1", questionDraftKey("http://h:3080|id", "s1/c1"))
        assertEquals("null/s1/c1", questionDraftKey(null, "s1/c1"))
    }

    @Test
    fun `the date of a draft lives beside it and is recognised`() {
        val key = questionDraftKey("h", "s/c")
        val stamp = questionDraftStampKey(key)
        assertTrue(isQuestionDraftStampKey(stamp))
        assertEquals(false, isQuestionDraftStampKey(key))
        assertEquals(key, stamp.removeSuffix("#savedAt"))
    }

    @Test
    fun `a draft a live card owns is never swept however old`() {
        val plan = sweepQuestionDrafts(
            drafts = listOf(StoredDraft("h/s/live", savedAt = now - 100 * 24 * hour)),
            liveKeys = setOf("h/s/live"),
            now = now,
        )
        assertTrue(plan.delete.isEmpty())
        assertTrue(plan.stampNow.isEmpty())
    }

    @Test
    fun `an orphan older than the ttl is deleted`() {
        val plan = sweepQuestionDrafts(
            drafts = listOf(StoredDraft("h/s/gone", savedAt = now - 25 * hour)),
            liveKeys = emptySet(),
            now = now,
        )
        assertEquals(listOf("h/s/gone"), plan.delete)
        assertTrue(plan.stampNow.isEmpty())
    }

    @Test
    fun `a recent orphan is kept, since its card may be about to come back`() {
        val plan = sweepQuestionDrafts(
            drafts = listOf(StoredDraft("h/s/maybe", savedAt = now - 2 * hour)),
            liveKeys = emptySet(),
            now = now,
        )
        assertTrue(plan.delete.isEmpty())
        assertTrue(plan.stampNow.isEmpty())
    }

    @Test
    fun `an undated draft is dated now instead of being judged on nothing`() {
        val plan = sweepQuestionDrafts(
            drafts = listOf(StoredDraft("h/s/legacy", savedAt = null)),
            liveKeys = emptySet(),
            now = now,
        )
        assertTrue(plan.delete.isEmpty())
        assertEquals(listOf("h/s/legacy"), plan.stampNow)
    }

    @Test
    fun `the age limit is exclusive at the boundary`() {
        val plan = sweepQuestionDrafts(
            drafts = listOf(
                StoredDraft("h/s/exact", savedAt = now - QUESTION_DRAFT_TTL_MS),
                StoredDraft("h/s/over", savedAt = now - QUESTION_DRAFT_TTL_MS - 1),
            ),
            liveKeys = emptySet(),
            now = now,
        )
        assertEquals(listOf("h/s/over"), plan.delete)
    }

    @Test
    fun `a mixed store sorts into keep, stamp and delete`() {
        val plan = sweepQuestionDrafts(
            drafts = listOf(
                StoredDraft("h/s/live", savedAt = null),
                StoredDraft("h/s/old", savedAt = now - 48 * hour),
                StoredDraft("h/s/new", savedAt = now - hour),
                StoredDraft("h/s/legacy", savedAt = null),
            ),
            liveKeys = setOf("h/s/live"),
            now = now,
        )
        assertEquals(listOf("h/s/old"), plan.delete)
        assertEquals(listOf("h/s/legacy"), plan.stampNow)
    }
}
