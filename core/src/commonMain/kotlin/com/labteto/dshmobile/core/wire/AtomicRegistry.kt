package com.labteto.dshmobile.core.wire

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * A small thread-safe registry keyed by [K], standing in for `java.util.concurrent.ConcurrentHashMap`
 * in code that has to compile for iOS.
 *
 * The mux's logical streams are registered, looked up and retired from OkHttp's socket threads and
 * from coroutines at the same moment, so a plain `HashMap` is out and `ConcurrentHashMap` does not
 * exist in common code. Rather than guard a map with a lock, the whole map is an immutable value
 * held in one atomic reference: every change builds the next map and swaps it in with a
 * compare-and-set, retrying if another thread got there first. That costs a copy per change, which
 * is nothing at the size this is used for (a handful of open streams), and in return
 *
 * - no lock is ever held across a call out of this class, so the caller can `trySend` on a channel
 *   or invoke a listener with the registry in any state without risking a deadlock, and
 * - [remove] is atomic *and* returns what it removed: of any number of racing callers retiring the
 *   same key, exactly one gets the value back. The mux relies on that to decide who delivers a
 *   stream's terminal signal.
 *
 * Not a general-purpose map: only the five operations the mux needs, with the semantics it needs.
 * Values are non-null so that "absent" has one spelling.
 */
internal class AtomicRegistry<K, V : Any> {
    private val entries = atomic<Map<K, V>>(emptyMap())

    /** The value registered under [key], or null. */
    operator fun get(key: K): V? = entries.value[key]

    /** Whether anything is registered under [key]. */
    fun containsKey(key: K): Boolean = key in entries.value

    /** Register [value] under [key], replacing whatever was there. */
    operator fun set(key: K, value: V) {
        entries.update { it + (key to value) }
    }

    /**
     * Unregister [key] and return what was there, or null when nothing was.
     *
     * Atomic: when several threads race to remove one key, exactly one receives the value.
     */
    fun remove(key: K): V? {
        while (true) {
            val current = entries.value
            val previous = current[key] ?: return null
            if (entries.compareAndSet(current, current - key)) return previous
        }
    }

    /**
     * Empty the registry in one step and return everything that was in it.
     *
     * One atomic swap rather than a snapshot of the keys followed by a remove per key: a
     * registration cannot slip in between the two halves and be missed, and nothing returned here
     * can also be returned by a concurrent [remove].
     */
    fun removeAll(): List<V> = entries.getAndSet(emptyMap()).values.toList()
}
