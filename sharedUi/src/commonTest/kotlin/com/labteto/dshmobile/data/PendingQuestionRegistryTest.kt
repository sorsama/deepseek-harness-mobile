package com.labteto.dshmobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which request an answer is allowed to take away.
 *
 * Settling a card on this client's own receipt is the only exit it has — the harness tells every
 * client except the one that acted — so the store forgets the request itself. The trap is that an
 * answer travels by HTTP while the next request arrives on `$events`: a session asked again the
 * instant the last batch settles can have its registration replaced before the receipt lands. An
 * answer that forgot by session alone would then delete the *new* card and leave its request
 * pending on the harness with nothing able to answer it — the stuck card of 0.11.3, arrived at
 * from the other end.
 */
class PendingQuestionRegistryTest {

    private val registry = PendingQuestionRegistry()

    @Test
    fun `a session holds one request, looked up from either side`() {
        registry.install("s1", "evt-1")
        assertEquals("evt-1", registry.eventFor("s1"))
        // The reverse direction is what a `cancel` frame needs: it names an event and nothing else.
        assertEquals("s1", registry.sessionFor("evt-1"))
        assertNull(registry.eventFor("s2"))
        assertNull(registry.sessionFor("evt-missing"))
    }

    @Test
    fun `answering forgets the request that was answered`() {
        registry.install("s1", "evt-1")
        assertTrue(registry.forget("s1", "evt-1"))
        assertNull(registry.eventFor("s1"))
    }

    @Test
    fun `a late receipt cannot take away the request that replaced it`() {
        // The race in full: the answer to evt-1 is still in flight when evt-2 arrives for the same
        // session and takes the registration over.
        registry.install("s1", "evt-1")
        registry.install("s1", "evt-2")

        assertFalse("evt-1's receipt must not settle evt-2", registry.forget("s1", "evt-1"))
        assertEquals("evt-2", registry.eventFor("s1"))
    }

    @Test
    fun `forgetting twice is a no-op, so a cancel frame after an answer is harmless`() {
        // Both paths can fire for one request: this client answers and forgets, and the harness
        // may still push `cancel` for the same event if it had a second delivery open.
        registry.install("s1", "evt-1")
        assertTrue(registry.forget("s1", "evt-1"))
        assertFalse(registry.forget("s1", "evt-1"))
    }

    @Test
    fun `a null event forgets only a session that is holding nothing`() {
        // The corpse case: a card with no registration behind it can never be answered, so it is
        // taken away — unless a request has since landed in that same gap, which owns it now.
        assertTrue(registry.forget("s1", null))

        registry.install("s1", "evt-1")
        assertFalse("a real request must not be mistaken for a corpse", registry.forget("s1", null))
        assertEquals("evt-1", registry.eventFor("s1"))
    }

    @Test
    fun `a removed session takes its request with it`() {
        registry.install("s1", "evt-1")
        registry.install("s2", "evt-2")
        registry.discard("s1")
        assertNull(registry.eventFor("s1"))
        assertNull(registry.sessionFor("evt-1"))
        assertEquals("evt-2", registry.eventFor("s2"))
    }
}
