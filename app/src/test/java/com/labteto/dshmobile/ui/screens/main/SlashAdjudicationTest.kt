package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.CommandInputDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The composer's one decision: command or prompt.
 *
 * It matters both ways. A hit that is treated as a prompt reaches the model as text — which is how
 * picking a permission preset used to make the agent shell out and guess. A miss that is treated as
 * a command would break skills, which are invoked precisely by sending `/name` as a prompt.
 */
class SlashAdjudicationTest {

    private val catalog = listOf(
        CommandDescriptor(name = "compact", description = "Compact the conversation"),
        CommandDescriptor(
            name = "permission",
            description = "Switch the permission preset",
            input = CommandInputDescriptor(hint = "<preset>"),
        ),
        // `/goal` and `/plan` are the two commands harness 0.1.0-rc.8 taught to take images.
        CommandDescriptor(
            name = "goal",
            description = "Set a goal",
            input = CommandInputDescriptor(hint = "<text>", images = true),
        ),
    )

    private fun decide(draft: String, attachments: Int = 0, hostAcceptsImages: Boolean = true) =
        adjudicate(draft, catalog, attachments, hostAcceptsImages)

    @Test
    fun `ordinary text is a prompt`() {
        assertEquals(Submission.Prompt("build the thing"), decide("build the thing"))
    }

    @Test
    fun `a bare registered command is a command`() {
        assertEquals(Submission.Command("/compact"), decide("/compact"))
    }

    @Test
    fun `surrounding whitespace does not hide a command`() {
        assertEquals(Submission.Command("/compact"), decide("  /compact  "))
    }

    @Test
    fun `an argument-taking command claims the whole line`() {
        assertEquals(
            Submission.Command("/permission read-only"),
            decide("/permission read-only"),
        )
        assertEquals(Submission.Command("/goal ship it"), decide("/goal ship it"))
    }

    @Test
    fun `an argument-taking command invoked bare is still a command`() {
        assertEquals(Submission.Command("/permission"), decide("/permission"))
    }

    @Test
    fun `arguments on a command that takes none stay a prompt`() {
        // The host would drop the tail silently; better the model sees the sentence the user wrote.
        assertEquals(Submission.Prompt("/compact and summarise"), decide("/compact and summarise"))
    }

    @Test
    fun `an unregistered name falls through to the prompt path`() {
        // This is the skill path: the host's pre-step boundary resolves `/name` itself.
        assertEquals(Submission.Prompt("/artifact-design"), decide("/artifact-design"))
        assertEquals(Submission.Prompt("/my-skill do it"), decide("/my-skill do it"))
    }

    @Test
    fun `a bare slash is not a command`() {
        assertEquals(Submission.Prompt("/"), decide("/"))
        assertEquals(Submission.Prompt("/ compact"), decide("/ compact"))
    }

    @Test
    fun `names are matched case-sensitively, as the host parses them`() {
        assertEquals(Submission.Prompt("/Compact"), decide("/Compact"))
    }

    @Test
    fun `an empty catalog makes every line a prompt`() {
        assertEquals(Submission.Prompt("/compact"), adjudicate("/compact", emptyList(), 0, true))
    }

    @Test
    fun `a command that declares images takes them`() {
        assertEquals(Submission.Command("/goal ship it"), decide("/goal ship it", attachments = 1))
    }

    @Test
    fun `a command that declares no images refuses them rather than becoming a prompt`() {
        // This used to send the literal text "/compact" to the model alongside the picture, with
        // nothing on screen to say the command had been quietly demoted.
        assertEquals(
            Submission.Refused("compact", RefusalReason.COMMAND_TAKES_NO_IMAGES),
            decide("/compact", attachments = 1),
        )
        assertEquals(
            Submission.Refused("permission", RefusalReason.COMMAND_TAKES_NO_IMAGES),
            decide("/permission read-only", attachments = 1),
        )
    }

    @Test
    fun `a harness that cannot carry images says so rather than dropping them`() {
        assertEquals(
            Submission.Refused("goal", RefusalReason.HOST_TOO_OLD),
            decide("/goal ship it", attachments = 1, hostAcceptsImages = false),
        )
        // Without images the same command is unremarkable on either release.
        assertEquals(
            Submission.Command("/goal ship it"),
            decide("/goal ship it", hostAcceptsImages = false),
        )
    }

    @Test
    fun `the catalog is consulted before the images are`() {
        // An unregistered name is a skill, and a skill is invoked by sending it as a prompt — so
        // it keeps its images and goes to the model, exactly as it does without them.
        assertEquals(Submission.Prompt("/my-skill do it"), decide("/my-skill do it", attachments = 1))
        // And a command that never claimed the line does not get to refuse it either.
        assertEquals(
            Submission.Prompt("/compact and summarise"),
            decide("/compact and summarise", attachments = 1),
        )
    }

    @Test
    fun `sub-command grammar is left to the host`() {
        // `/plan off` with images is refused by the handler, not the composer — mirroring the rule
        // here would mean maintaining a copy of every command's grammar.
        assertEquals(Submission.Command("/goal clear"), decide("/goal clear", attachments = 1))
    }
}
