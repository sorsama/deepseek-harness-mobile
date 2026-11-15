package com.labteto.dshmobile.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalPromptTest {

    @Test fun aBashRequestFromTheRuntimeGateIsTheCommandAlone() {
        // Exactly what the runtime's permission.mjs sends: `<tool>: <command>` in reason, and a
        // question plus the command in displayReason.en (which the app would otherwise show).
        val prompt = approvalPromptOf("bash", "bash: rm -rf build", "Run this command?\nrm -rf build")
        assertEquals(ApprovalPrompt.Kind.Command, prompt.kind)
        assertEquals("rm -rf build", prompt.detail)
        assertFalse(prompt.shortened)
        assertNull(prompt.text)
    }

    @Test fun aLongMultiLineCommandIsKeptWhole() {
        val command = (1..40).joinToString("\n") { "echo line $it" } + "\ncat <<'EOF'\n  indented: yes\nEOF"
        val prompt = approvalPromptOf("bash", "bash: $command")
        assertEquals(command, prompt.detail)
        assertFalse(prompt.shortened)
    }

    @Test fun aCommandTheGateCutShortSaysSo() {
        val cut = "x".repeat(ApprovalPrompt.HARNESS_DETAIL_LIMIT) + "..."
        assertTrue(approvalPromptOf("bash", "bash: $cut").shortened)
        // A command that merely ends in three dots is not one that was cut.
        assertFalse(approvalPromptOf("bash", "bash: echo wait...").shortened)
    }

    @Test fun programsFilesAndPluginChangesHaveTheirOwnKinds() {
        assertEquals(ApprovalPrompt.Kind.Command, approvalPromptOf("run_code", "run_code: console.log(1)").kind)
        assertEquals(ApprovalPrompt.Kind.Command, approvalPromptOf("terminal_send", "terminal_send: ls").kind)
        val write = approvalPromptOf("write", "write: /root/.dsh/settings.yaml")
        assertEquals(ApprovalPrompt.Kind.FileChange, write.kind)
        assertEquals("/root/.dsh/settings.yaml", write.detail)
        assertEquals(ApprovalPrompt.Kind.Tool, approvalPromptOf("plugin_manager", "plugin_manager: {\"action\":\"set_plugin\"}").kind)
    }

    @Test fun anyOtherRequestKeepsTheHarnessWording() {
        // A desktop harness's sandbox escalation: the localized reason, as before.
        val escalation = approvalPromptOf("bash", "needs network access to fetch dependencies", "需要网络访问")
        assertEquals(ApprovalPrompt.Kind.Plain, escalation.kind)
        assertEquals("需要网络访问", escalation.text)
        assertNull(escalation.detail)
        // The prefix of another tool, or an unknown tool in the gate's format, is not a command.
        assertEquals(ApprovalPrompt.Kind.Plain, approvalPromptOf("bash", "write: /etc/hosts").kind)
        assertEquals(ApprovalPrompt.Kind.Plain, approvalPromptOf("web_fetch", "web_fetch: https://example.com").kind)
        // Nothing at all: the tool name is all there is, and the panel falls back to it.
        assertNull(approvalPromptOf("bash", null).text)
        assertEquals(ApprovalPrompt.Kind.Plain, approvalPromptOf("bash", "bash: ").kind)
    }
}
