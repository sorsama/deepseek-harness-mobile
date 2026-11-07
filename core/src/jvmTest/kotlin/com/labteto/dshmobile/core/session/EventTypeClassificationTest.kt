package com.labteto.dshmobile.core.session

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import org.junit.Assert.assertEquals

/**
 * Every durable event type the harness declares has to be one this client decided about.
 *
 * The harness generates its own vocabulary — `KNOWN_SESSION_EVENT_TYPES` in
 * `packages/core/session/src/known-event-types.ts`, written by `gen-persistence-catalog` and
 * verified fresh in its own CI — so the authoritative list is a file, not a guess. This test reads
 * it and compares it to [EventClassification] in both directions, which is the only way the
 * comparison is worth anything:
 *
 *  * a type the harness declares and this client has not classified would reach the transcript as
 *    a raw JSON row headed with its own event name, which is the compatibility contract for a type
 *    from the *future* but is just an oversight for one shipping today;
 *  * a type this client classifies that the harness no longer declares is a dead entry, and dead
 *    entries are how the real bug happened — the skip list still named `tool/code-dispatch` long
 *    after it was renamed `tool/ptc-dispatch`, so PTC dispatch metadata had been printing into the
 *    middle of people's transcripts, and nothing said a word.
 *
 * The test needs the harness sources, which are not vendored here, so it skips when they are
 * absent: set `DSH_HARNESS_SRC` to a checkout, or keep one beside this repo. Skipping is the right
 * default rather than a failure — CI has no harness checkout, and a test that cannot run is not the
 * same fact as a client that is wrong.
 */
class EventTypeClassificationTest {

    private fun harnessCheckout(): File? {
        val candidates = listOfNotNull(
            System.getenv("DSH_HARNESS_SRC"),
            "../deepseek-harness",
            "../../deepseek-harness",
            "G:/LAB/deepseek-harness",
        )
        return candidates.map { File(it, KNOWN_TYPES_PATH) }.firstOrNull { it.isFile }
    }

    /**
     * The `KNOWN_SESSION_EVENT_TYPES` members, read out of the generated TypeScript.
     *
     * Read lexically rather than by parsing TypeScript: the file is generated, so its shape is
     * stable, and every member sits alone on its line in quotes. Anything looser would match the
     * `MESSAGE_PROJECTION_EVENT_TYPES` set further down the same file, so the scan stops at the end
     * of the first `new Set([...])`.
     */
    private fun declaredTypes(source: File): Set<String> {
        val text = source.readText()
        val start = text.indexOf("KNOWN_SESSION_EVENT_TYPES")
        check(start >= 0) { "no KNOWN_SESSION_EVENT_TYPES in ${source.path}" }
        val open = text.indexOf("new Set([", start)
        val close = text.indexOf("])", open)
        check(open >= 0 && close > open) { "could not find the set literal in ${source.path}" }
        return Regex("'([^']+)'").findAll(text.substring(open, close))
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `every harness event type is classified, and nothing is classified that the harness dropped`() {
        val source = harnessCheckout()
        assumeTrue("no harness checkout; set DSH_HARNESS_SRC to run this", source != null)
        val declared = declaredTypes(source!!)
        check(declared.size > 40) { "read only ${declared.size} types; the scan is probably wrong" }

        val unclassified = (declared - EventClassification.CLASSIFIED).sorted()
        assertEquals(
            "the harness declares these and this client has not decided what to do with them",
            emptyList<String>(), unclassified,
        )

        // The legacy set is deliberately outside the harness's current vocabulary, so it is
        // excluded from the reverse check rather than being allowed to hide a stale entry.
        val stale = (EventClassification.CLASSIFIED - declared - EventClassification.FOLDED_LEGACY).sorted()
        assertEquals(
            "this client classifies these and the harness no longer declares them",
            emptyList<String>(), stale,
        )
    }

    @Test
    fun `no type is in two minds at once`() {
        val folded = EventClassification.FOLDED
        val logOnly = EventClassification.LOG_ONLY
        val passthrough = EventClassification.PASSTHROUGH
        assertEquals("folded and log-only overlap", emptySet<String>(), folded intersect logOnly)
        assertEquals("folded and passthrough overlap", emptySet<String>(), folded intersect passthrough)
        assertEquals("log-only and passthrough overlap", emptySet<String>(), logOnly intersect passthrough)
    }

    private companion object {
        const val KNOWN_TYPES_PATH = "packages/core/session/src/known-event-types.ts"
    }
}
