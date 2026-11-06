package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftPrefillTest {

    @Test
    fun `an empty or blank draft just takes the prefix`() {
        assertEquals("/review ", prefilledDraft("", "/review "))
        assertEquals("/review ", prefilledDraft("   ", "/review "))
        assertEquals("/review ", prefilledDraft("\n", "/review "))
    }

    @Test
    fun `typed text is kept and becomes the argument`() {
        assertEquals(
            "/review fix the flaky login test",
            prefilledDraft("fix the flaky login test", "/review "),
        )
    }

    @Test
    fun `a long multi line draft survives whole`() {
        val draft = "first line\nsecond line\n\nthird"
        assertEquals("/skill-name $draft", prefilledDraft(draft, "/skill-name "))
    }

    @Test
    fun `picking the same command twice does not stack it`() {
        assertEquals("/review fix it", prefilledDraft("/review fix it", "/review "))
        assertEquals("/review ", prefilledDraft("/review ", "/review "))
    }

    @Test
    fun `a bare slash word being typed is completed not duplicated`() {
        assertEquals("/review ", prefilledDraft("/rev", "/review "))
        assertEquals("/compact ", prefilledDraft("/", "/compact "))
    }

    @Test
    fun `a slash line with an argument is not rewritten`() {
        // Another command is already there with its argument: nothing is dropped, the pick leads.
        assertEquals("/compact /review fix it", prefilledDraft("/review fix it", "/compact "))
    }

    @Test
    fun `text that merely starts with a path is not mistaken for a command`() {
        assertEquals("/review /usr/bin/env is wrong", prefilledDraft("/usr/bin/env is wrong", "/review "))
    }
}
