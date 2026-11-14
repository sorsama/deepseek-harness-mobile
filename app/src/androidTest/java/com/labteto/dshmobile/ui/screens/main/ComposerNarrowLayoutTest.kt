package com.labteto.dshmobile.ui.screens.main

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposerNarrowLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val longDraft = (1..12).joinToString("\n") { "บรรทัดที่ $it ทดสอบภาษาไทยและ draft" }
    private val references = listOf("preview.md", "folder/", "another session").mapIndexed { index, name ->
        DraftReference(index, index + 1, "@$name", name, listOf("file", "folder", "session")[index], name)
    }
    private var pixelsPerDp = 1f

    @Test fun multilineThaiDraftWithThreeReferencesKeepsControlsOnNarrowPhone() {
        render(360.dp, 560.dp, 220.dp)
        assertControlsHaveHeight()
    }

    @Test fun multilineThaiDraftWithThreeReferencesKeepsControlsInLandscape() {
        render(640.dp, 360.dp, 180.dp, running = true)
        assertControlsHaveHeight()
        val stop = compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_stop))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("stop must retain its height", stop.height >= 40f * pixelsPerDp - 1f)
    }

    @Test fun unboundedHeightKeepsEditorAndReferenceActionsUsable() {
        val opened = mutableListOf<String>()
        var additions = 0
        render(360.dp, 560.dp, null, onOpenReference = { opened.add(it.target) }, onAddReference = { additions++ })
        assertControlsHaveHeight()
        // Match the named action; the visual label now contains display-only bidi isolates.
        compose.onNodeWithContentDescription(context.getString(R.string.ux_b_open_reference,
            context.getString(R.string.ux_b_file), "preview.md")).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.ux_b_open_reference,
            context.getString(R.string.ux_b_session), "another session")).performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.harness_add_reference)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("preview.md", "another session"), opened)
            assertEquals(1, additions)
        }
    }

    /**
     * The height #87 was reported at: a long pasted prompt in a composer bounded to the 45% cap
     * with the keyboard open, mid-turn so send *and* stop are both on screen. Every affordance has
     * to be on the screen — displayed, clickable, and inside the composer rather than measured at
     * zero height below it — which is what the 0.19.0 redesign broke.
     */
    @Test fun longPastedDraftAtThePostImeCapKeepsEveryActionReachable() {
        render(360.dp, 378.dp, 180.dp, running = true)
        assertControlsHaveHeight()
        listOf(
            R.string.chat_composer_send,
            R.string.chat_composer_stop,
            R.string.harness_add_reference,
            R.string.chat_composer_commands,
            R.string.chat_composer_expand,
        ).forEach { label ->
            val node = compose.onNodeWithContentDescription(context.getString(label))
            node.assertIsDisplayed()
            node.assertHasClickAction()
            assertTrue(
                "$label must stay inside the screen with the keyboard open",
                node.fetchSemanticsNode().boundsInRoot.bottom <= 378f * pixelsPerDp + 1f,
            )
        }
    }

    /** Wrapped lines count, not just hard breaks: one long paragraph is enough to lift the drawer. */
    @Test fun oneLongWrappedParagraphOpensTheFullScreenEditor() {
        var draft by mutableStateOf("")
        compose.setContent {
            DshTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    Composer(draft, { draft = it }, emptyList(), {}, {}, null, null, {}, null, null,
                        running = false, enabled = true, onOpenSheet = {}, onStop = {}, onSend = {})
                }
            }
        }
        val expand = context.getString(R.string.chat_composer_expand)
        compose.runOnIdle { draft = "one line" }
        compose.onNodeWithContentDescription(expand).assertDoesNotExist()

        compose.runOnIdle { draft = "word ".repeat(120).trim() }
        compose.onNodeWithContentDescription(expand).assertIsDisplayed()

        // The page opens on the same draft, closes without touching it, and the button goes away
        // with the drawer when the draft no longer needs either.
        compose.onNodeWithContentDescription(expand).performClick()
        awaitPage(open = true)
        compose.onNodeWithText(context.getString(R.string.chat_composer_fullscreen_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        awaitPage(open = false)
        compose.onNodeWithText(context.getString(R.string.chat_composer_fullscreen_title)).assertDoesNotExist()
        compose.runOnIdle { assertTrue("the page must not edit the draft on its way out", draft.startsWith("word ")) }
        compose.runOnIdle { draft = "back to one line" }
        compose.onNodeWithContentDescription(expand).assertDoesNotExist()
    }

    private fun failedFile(name: String = "ui.xml", id: String = name) = PendingAttachment.File(
        id = id, uri = Uri.parse("content://test/$name"), name = name, size = 2048,
        state = FileUploadState.Failed("nope"),
    )

    /**
     * An attachment at the 180dp post-IME cap, the tightest height the chat screen gives the
     * composer: the tray unfolds above the card, counts in the block's height, and still leaves the
     * card its action row. Every action is displayed and clickable, nothing leaves the screen, and
     * the chip is above the card rather than between the text and the row.
     *
     * The cap here is the bare 180dp, with no allowance for the tray, which is the worst a caller
     * can do to the card: the tray is measured first, so the card (and above all its action row)
     * is what gets squeezed. No reference chips, so the editor still has a line left to be visible.
     */
    @Test fun attachmentAtThePostImeCapKeepsEveryActionAndSitsAboveTheCard() {
        render(360.dp, 378.dp, 180.dp, running = true, attachments = listOf(failedFile()), withReferences = false)
        assertActionsReachableWithTheTrayAboveTheCard()
    }

    /**
     * What the chat screen really hands the composer when something is attached: the 180dp cap plus
     * [AttachmentTrayHeight], so the card keeps the room it has without a tray. With three reference
     * chips in the card as well, the editor and every action are all still on screen.
     */
    @Test fun attachmentWithTheChatScreensTrayAllowanceLeavesTheCardItsRoom() {
        render(360.dp, 378.dp, 180.dp + AttachmentTrayHeight, running = true, attachments = listOf(failedFile()))
        assertActionsReachableWithTheTrayAboveTheCard()
    }

    private fun assertActionsReachableWithTheTrayAboveTheCard() {
        assertControlsHaveHeight()
        listOf(
            R.string.chat_composer_send,
            R.string.chat_composer_stop,
            R.string.harness_add_reference,
            R.string.chat_composer_commands,
            R.string.chat_composer_expand,
        ).forEach { label ->
            val node = compose.onNodeWithContentDescription(context.getString(label))
            node.assertIsDisplayed()
            node.assertHasClickAction()
            assertTrue(
                "$label must stay inside the screen with the keyboard open",
                node.fetchSemanticsNode().boundsInRoot.bottom <= 378f * pixelsPerDp + 1f,
            )
        }
        val chip = compose.onNodeWithText("ui.xml").also { it.assertIsDisplayed() }.fetchSemanticsNode().boundsInRoot
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_commands))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("the tray must be above the text, not between it and the actions: $chip vs $field", chip.bottom <= field.top + 1f)
        assertTrue("the tray must be above the action row: $chip vs $actions", chip.bottom <= actions.top)
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_remove_file)).assertIsDisplayed()
    }

    /** One line and no files: no tray exists at all, so that composer looks as it always did. */
    @Test fun noTrayWithoutAttachments() {
        render(360.dp, 560.dp, null)
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_remove_file)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_remove_image)).assertDoesNotExist()
    }

    /** Tapping a failed chip retries it, the badge removes it, and the tray folds away with the last one. */
    @Test fun trayChipsRetryAndRemoveAndTheTrayFoldsAway() {
        val files = mutableStateListOf(failedFile("a.txt"), failedFile("b.txt"))
        val retried = mutableListOf<Int>()
        render(360.dp, 560.dp, null, attachments = files, onRetry = { retried.add(it) }, onRemove = { files.removeAt(it) })

        // Two 240dp chips do not both fit a 360dp phone: the second is there, scrolled partly out of view.
        compose.onNodeWithText("a.txt").assertIsDisplayed()
        compose.onNodeWithText("b.txt").assertExists()
        compose.onAllNodesWithText(context.getString(R.string.chat_attachment_upload_failed))[0].performClick()
        compose.runOnIdle { assertEquals(listOf(0), retried) }

        val remove = context.getString(R.string.chat_composer_remove_file)
        compose.onAllNodesWithContentDescription(remove)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("a.txt").assertDoesNotExist()
        compose.onNodeWithText("b.txt").assertIsDisplayed()

        compose.onAllNodesWithContentDescription(remove)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("b.txt").assertDoesNotExist()
        compose.onNodeWithContentDescription(remove).assertDoesNotExist()
    }

    /** The page shows the same tray, with the same remove and retry. */
    @Test fun fullScreenPageShowsTheSameTray() {
        val files = mutableStateListOf(failedFile("page.txt"))
        var draft by mutableStateOf("word ".repeat(120).trim())
        val retried = mutableListOf<Int>()
        compose.setContent {
            DshTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    Composer(draft, { draft = it }, files, { files.removeAt(it) }, { retried.add(it) }, null, null, {}, null, null,
                        running = false, enabled = true, onOpenSheet = {}, onStop = {}, onSend = {})
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_expand)).performClick()
        awaitPage(open = true)
        compose.onNodeWithText(context.getString(R.string.chat_composer_fullscreen_title)).assertIsDisplayed()
        // The card behind the page keeps its own chip; the page adds a second one.
        val chips = compose.onAllNodesWithText("page.txt")
        assertTrue("the page must show the chip too", chips.fetchSemanticsNodes().size >= 2)
        val pageChip = chips[chips.fetchSemanticsNodes().size - 1]
        val field = compose.onAllNodes(hasSetTextAction())[compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size - 1]
        assertTrue(
            "the page's tray must be above its editor",
            pageChip.fetchSemanticsNode().boundsInRoot.bottom <= field.fetchSemanticsNode().boundsInRoot.top + 1f,
        )
        compose.onAllNodesWithText(context.getString(R.string.chat_attachment_upload_failed)).onLast().performClick()
        compose.runOnIdle { assertEquals(listOf(0), retried) }
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        awaitPage(open = false)
        compose.onNodeWithText(context.getString(R.string.chat_composer_fullscreen_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, files.size) }
    }

    /**
     * The page rises in and sinks away, so it is on screen a moment after the tap and gone a moment
     * after back, not on the same frame. Waiting on the title is what a person does too.
     */
    private fun awaitPage(open: Boolean) {
        val title = context.getString(R.string.chat_composer_fullscreen_title)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() == open
        }
        compose.waitForIdle()
    }

    private fun render(
        width: Dp, height: Dp, composerHeight: Dp?, running: Boolean = false,
        onOpenReference: (DraftReference) -> Unit = {}, onAddReference: () -> Unit = {},
        attachments: List<PendingAttachment> = emptyList(),
        onRemove: (Int) -> Unit = {}, onRetry: (Int) -> Unit = {},
        withReferences: Boolean = true,
        draft: String = longDraft,
        permission: String = "custom",
        pressure: ContextPressureView? = null,
    ) {
        compose.setContent {
            BoxWithConstraints {
                // Fit both device shapes on the assigned emulator without rotating or changing it.
                val density = Density(minOf(LocalDensity.current.density,
                    constraints.maxWidth / width.value, constraints.maxHeight / height.value))
                SideEffect { pixelsPerDp = density.density }
                CompositionLocalProvider(LocalDensity provides density) {
                    DshTheme {
                        Box(Modifier.size(width, height)) {
                            Column(if (composerHeight == null) Modifier.verticalScroll(rememberScrollState()) else Modifier) {
                                if (composerHeight != null) Spacer(Modifier.height(height - composerHeight))
                                Composer(
                                    draft = draft, onDraftChange = {}, attachments = attachments,
                                    onRemoveAttachment = onRemove, onRetryAttachment = onRetry,
                                    permissions = PermissionSelect(currentValue = permission), pendingPermission = null, onPermissionPick = {},
                                    contextBreakdown = null, contextPressure = pressure,
                                    running = running, enabled = true, onOpenSheet = {}, onSend = {}, onStop = {},
                                    references = if (withReferences) references else emptyList(),
                                    onOpenReference = onOpenReference, onAddReference = onAddReference,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- The permission chip and the context meter share the action row's flexible slot ----

    private val workspaceWrite get() = context.getString(R.string.permission_preset_workspace_write)

    /** The 10-turn demo session: a pressure reading, but a fill 0.15dp wide in a 44dp bar. */
    private val nearlyEmpty = ContextPressureView(projectedTokens = 3_500, contextWindow = 1_000_000)

    /** Well past the point where the bar's fill is visible. */
    private val filling = ContextPressureView(projectedTokens = 620_000, contextWindow = 1_000_000)

    /** What the meter says about itself for a given reading: the nearly empty one says 0%, the filling one 62%. */
    private fun meterDescription(percent: Int) = context.getString(R.string.chat_context_used, percent)

    /** One line, as typed in the screenshots, so the row is the only thing the width has to serve. */
    private fun renderRow(width: Dp, pressure: ContextPressureView?, running: Boolean = false) =
        render(width, 640.dp, null, running = running, withReferences = false, draft = "hi",
            permission = "workspace-write", pressure = pressure)

    /** The chip's label bounds, and its text layout so a test can ask whether it got an ellipsis. */
    private fun chipLabel(): Pair<Rect, TextLayoutResult> {
        compose.waitForIdle()
        val label = compose.onNodeWithText(workspaceWrite, useUnmergedTree = true)
        label.assertIsDisplayed()
        val node = label.fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        val read = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        assertNotNull("the chip's label must expose its text layout", read)
        read!!(layouts)
        return node.boundsInRoot to layouts.first()
    }

    private fun chipLabelBounds() = chipLabel().first

    private fun assertChipSaysItsWholeName() {
        val (bounds, layout) = chipLabel()
        // The semantic text is always the full string, so what is checked is the layout: a label
        // that did not fit is ellipsized ("Works…"), which is what the phone screenshots showed.
        val cut = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
        assertFalse(
            "\"$workspaceWrite\" was cut short at $bounds " +
                "(lines=${layout.lineCount}, size=${layout.size}, visualOverflow=${layout.hasVisualOverflow})",
            cut,
        )
        assertEquals("the label must be on one line", 1, layout.lineCount)
    }

    /** Every meter the row has for a [percent] reading. None means none was composed, not just none drawn. */
    private fun meterBounds(percent: Int) = compose.onAllNodesWithContentDescription(meterDescription(percent))
        .fetchSemanticsNodes().map { it.boundsInRoot }

    /** The Flip's inner screen. A pressure reading with a sliver of fill must not cost the chip its name. */
    @Test fun permissionChipKeepsItsFullLabelOnAFlipInnerScreen() {
        renderRow(411.dp, nearlyEmpty)
        assertChipSaysItsWholeName()
    }

    @Test fun permissionChipKeepsItsFullLabelOnTheNarrowestPhone() {
        renderRow(360.dp, nearlyEmpty)
        assertChipSaysItsWholeName()
        assertChipHasRoomToSpare()
    }

    /**
     * At 360dp the label fits the slot with a hair to spare on one Android version and not on the
     * next: the Android 17 font is about 2dp wider than Android 15's, and the label first read
     * "Workspace Wri…" on a Flip while a test on the older image passed. So "fits" is not enough
     * here; the chip has to leave a margin that covers the difference between fonts.
     */
    private fun assertChipHasRoomToSpare(spare: Dp = 2.dp) {
        val chip = compose.onNodeWithText(workspaceWrite).fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_send))
            .fetchSemanticsNode().boundsInRoot
        val gap = 8f * pixelsPerDp
        val left = send.left - gap - chip.right
        assertTrue(
            "the chip fills its slot to within ${left / pixelsPerDp}dp at 360dp; it needs $spare of room " +
                "so a wider font does not cut the label (chip $chip, send $send)",
            left >= spare.value * pixelsPerDp - 1f,
        )
    }

    @Test fun anAlmostEmptyContextWindowDrawsNoStrayBarInTheRow() {
        renderRow(640.dp, nearlyEmpty)
        compose.waitForIdle()
        assertEquals("a reading with nothing to draw must leave the row alone", emptyList<Any>(), meterBounds(0))
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_send)).assertIsDisplayed()
    }

    /** With the room, a filling window is shown between the chip and send, and says what it shows. */
    @Test fun aFillingContextWindowGetsItsMeterBesideSendWhenThereIsRoom() {
        renderRow(640.dp, filling)
        assertChipSaysItsWholeName()
        val meter = meterBounds(62).single()
        val chip = chipLabelBounds()
        val send = compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_send))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("the meter must sit right of the chip: $meter vs $chip", meter.left >= chip.right)
        assertTrue("the meter must sit left of send: $meter vs $send", meter.right <= send.left)
        compose.onNodeWithContentDescription(meterDescription(62)).assertIsDisplayed()
    }

    /**
     * The meter takes only what the chip leaves: a row too tight for both shows the chip whole and
     * drops the bar, and never the other way round. Where the bar does fit it must not overlap the
     * chip. The widths are the ones the label is checked at above, with the window really filling.
     */
    private fun assertTheMeterNeverCostsTheChipItsName(width: Dp) {
        renderRow(width, filling)
        assertChipSaysItsWholeName()
        val chip = chipLabelBounds()
        meterBounds(62).forEach { meter ->
            assertTrue("the meter overlaps the chip at $width: $meter vs $chip", meter.left >= chip.right)
        }
    }

    @Test fun aFillingWindowLeavesTheChipItsNameOnTheNarrowestPhone() = assertTheMeterNeverCostsTheChipItsName(360.dp)

    @Test fun aFillingWindowLeavesTheChipItsNameOnAFlipInnerScreen() = assertTheMeterNeverCostsTheChipItsName(411.dp)

    private fun assertControlsHaveHeight() {
        compose.waitForIdle()
        val send = compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_send))
        send.assertIsDisplayed()
        send.assertHasClickAction()
        val sendBounds = send.fetchSemanticsNode().boundsInRoot
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("send was vertically squeezed: $sendBounds", sendBounds.height >= 40f * pixelsPerDp - 1f)
        assertTrue("editor must keep visible height: $field", field.height > 0f)
    }
}
