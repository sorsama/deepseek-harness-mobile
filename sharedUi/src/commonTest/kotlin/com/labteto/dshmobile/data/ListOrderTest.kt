package com.labteto.dshmobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The move arithmetic behind drag-to-reorder: what "insert X before Y" does to a list. */
class ListOrderTest {

    private val abcd = listOf("a", "b", "c", "d")

    @Test
    fun `moving down lands in front of the anchor`() {
        assertEquals(listOf("b", "c", "a", "d"), moveBefore(abcd, "a", "d"))
    }

    @Test
    fun `moving up lands in front of the anchor`() {
        assertEquals(listOf("a", "d", "b", "c"), moveBefore(abcd, "d", "b"))
    }

    @Test
    fun `a null anchor appends`() {
        assertEquals(listOf("b", "c", "d", "a"), moveBefore(abcd, "a", null))
    }

    @Test
    fun `an unknown anchor appends like an absent one`() {
        assertEquals(listOf("b", "c", "d", "a"), moveBefore(abcd, "a", "zzz"))
    }

    @Test
    fun `an unknown id changes nothing`() {
        assertSame(abcd, moveBefore(abcd, "zzz", "a"))
    }

    @Test
    fun `an item anchored on itself changes nothing`() {
        assertSame(abcd, moveBefore(abcd, "b", "b"))
    }

    @Test
    fun `moving before the item that already follows is a no-op in effect`() {
        assertEquals(abcd, moveBefore(abcd, "b", "c"))
    }

    @Test
    fun `the anchor after a move is the next item, or null at the end`() {
        val moved = moveBefore(abcd, "a", "d")
        assertEquals("d", anchorAfterMove(moved, "a"))
        assertNull(anchorAfterMove(moveBefore(abcd, "a", null), "a"))
        assertNull(anchorAfterMove(abcd, "missing"))
    }

    @Test
    fun `an anchor round trips through the harness's insert-before`() {
        // What the drag sends is what the host will do: replaying the anchor on the old order
        // reproduces the order the screen is already showing.
        for ((id, to) in listOf("a" to 2, "d" to 0, "b" to 3, "c" to 1)) {
            val shown = abcd.toMutableList().apply { remove(id); add(to, id) }
            assertEquals(shown, moveBefore(abcd, id, anchorAfterMove(shown, id)))
        }
    }

    @Test
    fun `stepping moves one place and stops at the ends`() {
        assertEquals(listOf("a", "c", "b", "d"), moveByStep(abcd, "b", 1))
        assertEquals(listOf("b", "a", "c", "d"), moveByStep(abcd, "b", -1))
        assertNull(moveByStep(abcd, "a", -1))
        assertNull(moveByStep(abcd, "d", 1))
        assertNull(moveByStep(abcd, "b", 0))
        assertNull(moveByStep(abcd, "zzz", 1))
    }

    @Test
    fun `a saved order is applied and keeps the reader's arrangement`() {
        assertEquals(listOf("c", "a", "b"), applySavedOrder(listOf("a", "b", "c"), listOf("c", "a", "b")))
    }

    @Test
    fun `sessions never placed come first, in their given order`() {
        assertEquals(
            listOf("n1", "n2", "c", "a"),
            applySavedOrder(listOf("a", "n1", "c", "n2"), listOf("c", "a")),
        )
    }

    @Test
    fun `saved ids that no longer exist drop out`() {
        assertEquals(listOf("b", "a"), applySavedOrder(listOf("a", "b"), listOf("gone", "b", "a", "b")))
    }

    @Test
    fun `an empty saved order leaves the default alone`() {
        val given = listOf("a", "b")
        assertSame(given, applySavedOrder(given, emptyList()))
    }
}
