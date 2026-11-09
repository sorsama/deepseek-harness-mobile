package com.labteto.dshmobile.ui.screens.main

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.CommandInputDescriptor
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.core.wire.dto.PresetOption
import com.labteto.dshmobile.core.wire.dto.SkillEntry
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Evidence capture for the two redesigned sheets. Not a behavioural test: it renders the real
 * composables with a catalog the size of a well-stocked machine and writes the device screen to
 * `files/sheets/`, which `adb pull` collects.
 */
class SheetScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val commands = listOf(
        CommandDescriptor("compact", "Compact older conversation history"),
        CommandDescriptor("export", "Download this Session log as a ZIP archive"),
        CommandDescriptor("feedback", "Record feedback about this session", CommandInputDescriptor("<text>")),
        CommandDescriptor("goal", "Set or view the goal for a long-running task", CommandInputDescriptor("[<objective>|clear]")),
        CommandDescriptor("permission", "Switch the permission preset (sandbox mode + approval policy)", CommandInputDescriptor("<preset>")),
        CommandDescriptor("plan", "Enter or leave plan mode", CommandInputDescriptor("[off|message]")),
        CommandDescriptor("tarocub", "Show TaroCub Feishu/Lark bridge setup and verification guidance"),
        CommandDescriptor("rewind", "Return the session to an earlier message", CommandInputDescriptor("<step>")),
        CommandDescriptor("clear", "Clear the conversation history"),
        CommandDescriptor("model", "Switch the model for this session", CommandInputDescriptor("<model>")),
        CommandDescriptor("status", "Show the session status"),
        CommandDescriptor("context", "Show what the context window currently holds"),
        CommandDescriptor("agents", "List the available subagents"),
        CommandDescriptor("jobs", "List background jobs and their output"),
        CommandDescriptor("terminal", "Open the workspace terminal"),
        CommandDescriptor("theme", "Change the app appearance", CommandInputDescriptor("[dark|light]")),
        CommandDescriptor("language", "Change the app language", CommandInputDescriptor("<code>")),
        CommandDescriptor("help", "List every command this harness accepts"),
    )

    private val skills = listOf(
        SkillEntry("diagnose-windows-sandbox-acl", "Use on Windows for unexpected DSH sandbox access denials: workspace writes or listing fail, or an ordinarily readable path cannot be read.", null, true),
        SkillEntry("office", "Create, edit, inspect, or convert Office documents, spreadsheets, presentations, and PDFs.", null, true),
        SkillEntry("office-docx", "Create, read, edit, and validate Word documents (.docx).", null, true),
        SkillEntry("office-export-pdf", "Convert an Office document to a requested PDF with LibreOffice Kit.", null, true),
        SkillEntry("office-pptx", "Create, read, edit, and validate PowerPoint presentations (.pptx).", null, true),
        SkillEntry("office-recalculate", "Refresh spreadsheet formula caches and verify calculated values with LibreOffice Kit.", null, true),
        SkillEntry("office-render", "Render requested document images or inspect visual layout with LibreOffice Kit.", null, true),
        SkillEntry("office-xlsx", "Read, create, edit, and validate Excel workbooks, data, and formulas (.xlsx).", null, true),
        SkillEntry("release-notes", "Draft release notes from the commits since the last tag.", null, false),
        SkillEntry("lab-bench", "Local-only rig checklist for the hardware bench.", null, false),
    )

    private val permissions = PermissionSelect(
        options = listOf(
            PresetOption("read-only", "read-only", "Read files and answer. Nothing is changed."),
            PresetOption("workspace-write", "workspace-write", "Edits inside the workspace. Commands still ask."),
            PresetOption("danger-full-access", "danger-full-access", "Anything on the computer. No approval prompts."),
        ),
        currentValue = "danger-full-access",
    )

    @Test
    fun commandsSheet() {
        compose.setContent {
            DshTheme { Backdrop { HostedCommandSheet() } }
        }
        capture("01-commands")
        compose.onNodeWithText("Skills (10)").performClick()
        capture("02-skills")
    }

    @Test
    fun permissionSheet() {
        compose.setContent {
            DshTheme {
                Backdrop {
                    PermissionMenu(
                        select = permissions,
                        current = "danger-full-access",
                        onDismiss = {},
                        onPick = {},
                    )
                }
            }
        }
        capture("03-permission")
    }

    @Composable
    private fun HostedCommandSheet() {
        CommandSheet(
            commands = commands,
            commandsAvailable = true,
            skills = skills,
            mode = "queue",
            running = false,
            canAttach = true,
            onModeChange = {},
            onAttach = {},
            onAttachFile = {},
            onRunCommand = {},
            onPrefillDraft = {},
            onDismiss = {},
        )
    }

    /** Something for the scrim to dim, so the capture reads like the app and not like a bare window. */
    @Composable
    private fun Backdrop(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xFFF6F4EF))) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Add pagination to the todos API", color = Color(0xFF1B1A17))
                Text("All 6 tests pass. next is null on the last page.", color = Color(0xFF6B6862))
                Text("The paging cursor now comes from the API instead of the list length.", color = Color(0xFF6B6862))
            }
            content()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(700)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(instrumentation.targetContext.filesDir, "sheets").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
