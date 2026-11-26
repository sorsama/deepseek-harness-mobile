package com.labteto.dshmobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The termination guard for scroll-driven history paging.
 *
 * The transcript asks for an older page whenever the reader is near the top, so "is there more"
 * is no longer a question a person answers by tapping — it decides whether the app keeps asking.
 * A host that reports `hasMore` on a page a `beforeSeq` query cannot advance past would otherwise
 * loop forever.
 */
class HistoryPagingTest {

    @Test
    fun `a page that added nothing ends the paging even when the host says otherwise`() {
        assertFalse(nextHasMore(freshCount = 0, hostHasMore = true, overDelivered = true))
        assertFalse(nextHasMore(freshCount = 0, hostHasMore = true, overDelivered = false))
        assertFalse(nextHasMore(freshCount = 0, hostHasMore = false, overDelivered = false))
    }

    @Test
    fun `a page that added events keeps the host's verdict`() {
        assertTrue(nextHasMore(freshCount = 12, hostHasMore = true, overDelivered = false))
        assertFalse(nextHasMore(freshCount = 12, hostHasMore = false, overDelivered = false))
    }

    /**
     * Some builds ignore `maxMessages` and answer with the whole log; the client trims and reports
     * the remainder as more to come, which must survive even when the host claims completeness.
     */
    @Test
    fun `a trimmed over-delivery counts as more to come`() {
        assertTrue(nextHasMore(freshCount = 60, hostHasMore = false, overDelivered = true))
    }
}
