package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why ChatScreen hands the composer `{ text -> send(text) }` rather than `::send`.
 *
 * The composer keeps its send callback with `rememberUpdatedState`, which is
 * `remember { mutableStateOf(value) }.apply { this.value = value }` under the default structural
 * policy: a new value equal to the one held is not stored. `send` is a local function capturing the
 * open session's attachment list, and a reference to a local function equals every other reference
 * to it whatever it captured. So after a session change the button went on calling the first
 * session's `send`, reading a list nothing on screen showed — the picture stayed in the strip and
 * the message went out as text. These pin both halves of that; the call site itself cannot be
 * driven without a Compose UI harness.
 */
class SendCallbackTest {

    private fun referenceTo(attachments: MutableList<String>): (String) -> Unit {
        fun send(text: String) {
            attachments += text
        }
        return ::send
    }

    private fun lambdaCalling(attachments: MutableList<String>): (String) -> Unit {
        fun send(text: String) {
            attachments += text
        }
        return { text -> send(text) }
    }

    @Test
    fun `a reference for the next session is not stored over the first`() {
        val first = mutableListOf<String>()
        val next = mutableListOf<String>()
        val held = mutableStateOf(referenceTo(first))

        held.value = referenceTo(next)
        held.value("photo")

        assertEquals(listOf("photo"), first)
        assertTrue(next.isEmpty())
    }

    @Test
    fun `a lambda for the next session replaces the first`() {
        val first = mutableListOf<String>()
        val next = mutableListOf<String>()
        val held = mutableStateOf(lambdaCalling(first))

        held.value = lambdaCalling(next)
        held.value("photo")

        assertTrue(first.isEmpty())
        assertEquals(listOf("photo"), next)
    }
}
