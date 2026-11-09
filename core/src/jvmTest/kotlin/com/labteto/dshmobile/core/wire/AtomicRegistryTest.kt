package com.labteto.dshmobile.core.wire

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mux leans on two properties of its stream registry that a lock-free map has to earn: a
 * removal that hands the value to exactly one of several racing callers, and a sweep that cannot
 * miss a registration. A single-threaded test would pass a broken implementation, so the races are
 * real threads, and each outcome is counted rather than eyeballed.
 */
class AtomicRegistryTest {

    @Test
    fun `it behaves as a map for one thread`() {
        val registry = AtomicRegistry<String, Int>()
        assertNull(registry["a"])
        assertFalse(registry.containsKey("a"))

        registry["a"] = 1
        registry["b"] = 2
        registry["a"] = 3
        assertEquals(3, registry["a"])
        assertTrue(registry.containsKey("b"))

        assertEquals(3, registry.remove("a"))
        assertNull(registry.remove("a"))
        assertFalse(registry.containsKey("a"))

        assertEquals(listOf(2), registry.removeAll())
        assertEquals(emptyList<Int>(), registry.removeAll())
        assertNull(registry["b"])
    }

    @Test
    fun `of several threads removing the same key exactly one gets the value`() {
        val keys = 300
        val threads = 8
        val registry = AtomicRegistry<Int, Int>()
        repeat(keys) { registry[it] = it }

        val wins = Array(keys) { AtomicInteger() }
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                start.await()
                for (key in 0 until keys) {
                    if (registry.remove(key) != null) wins[key].incrementAndGet()
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))

        for (key in 0 until keys) assertEquals("key $key", 1, wins[key].get())
        assertEquals(emptyList<Int>(), registry.removeAll())
    }

    @Test
    fun `a sweep racing registrations and removals loses nothing and returns nothing twice`() {
        val total = 4_000
        val registry = AtomicRegistry<Int, Int>()
        val retired = ConcurrentLinkedQueue<Int>()
        val producerDone = AtomicBoolean(false)
        val start = CountDownLatch(1)

        val producer = Thread {
            start.await()
            for (key in 0 until total) registry[key] = key
            producerDone.set(true)
        }
        val removers = List(4) {
            Thread {
                start.await()
                while (!producerDone.get()) {
                    for (key in 0 until total step 7) registry.remove(key)?.let(retired::add)
                }
            }
        }
        val sweeper = Thread {
            start.await()
            while (!producerDone.get()) retired.addAll(registry.removeAll())
        }

        val all = removers + sweeper + producer
        all.forEach { it.start() }
        start.countDown()
        all.forEach { it.join(30_000) }
        assertTrue(all.none { it.isAlive })

        // Whatever the removers and the sweeper missed is still registered.
        retired.addAll(registry.removeAll())

        val seen = retired.sorted()
        assertEquals("every registration retired exactly once", (0 until total).toList(), seen)
    }
}
