package com.labteto.dshmobile.ui.screens.runtime

import com.labteto.dshmobile.runtime.RuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureLogTest {
    @Test
    fun `the rollback notice starts at the error line, not at the end of the stack`() {
        val tail = buildString {
            append("node:internal/modules/cjs/loader:1568\n  throw err;\n  ^\n\n")
            append("Error: Cannot find module '/opt/missing.js'\nRequire stack:\n- internal/preload\n")
            repeat(10) { append("    at frame$it (node:internal/x:$it)\n") }
            append("Node.js v24.22.3")
        }
        val excerpt = failureLog(RuntimeFailure.ExitedBeforeReady(1, tail))!!
        assertTrue(excerpt.startsWith("Error: Cannot find module"))
        assertEquals(8, excerpt.lines().size)
    }

    @Test
    fun `without an error line the last lines are shown`() {
        val tail = (1..20).joinToString("\n") { "line $it" }
        assertEquals((13..20).joinToString("\n") { "line $it" }, failureLog(RuntimeFailure.ReadyTimeout(tail)))
        assertNull(failureLog(RuntimeFailure.ReadyTimeout("  ")))
        assertNull(failureLog(RuntimeFailure.Network("x")))
    }
}
