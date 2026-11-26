package com.labteto.dshmobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalQueueTest {

    @Test
    fun `the oldest open request is shown first`() {
        assertEquals("a", shownApprovalId(null, listOf("a", "b")))
    }

    @Test
    fun `a newer request never replaces the one on screen`() {
        // Two commands asked for at once: the panel keeps the first until it is answered.
        assertEquals("a", shownApprovalId("a", listOf("a", "b")))
        assertEquals("b", shownApprovalId("b", listOf("a", "b")))
    }

    @Test
    fun `once answered the next one in line takes its place`() {
        assertEquals("b", shownApprovalId("a", listOf("b", "c")))
        assertNull(shownApprovalId("a", emptyList()))
    }
}
