package com.labteto.dshmobile.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.permission.PermissionDenial
import com.labteto.dshmobile.ui.components.ContentBlockView
import com.labteto.dshmobile.ui.components.DetailField
import com.labteto.dshmobile.ui.components.DetailItem
import com.labteto.dshmobile.ui.components.DetailLabel
import com.labteto.dshmobile.ui.components.DetailState
import com.labteto.dshmobile.ui.components.DetailValue
import com.labteto.dshmobile.ui.components.DetailsCard
import com.labteto.dshmobile.ui.components.DetailsTool
import com.labteto.dshmobile.ui.components.DiffHunk
import com.labteto.dshmobile.ui.components.DisclosureState
import com.labteto.dshmobile.ui.components.QuestionCard
import com.labteto.dshmobile.ui.components.QuestionLine
import com.labteto.dshmobile.ui.components.QuestionOutcome
import com.labteto.dshmobile.ui.components.ReadLine
import com.labteto.dshmobile.ui.components.SearchFile
import com.labteto.dshmobile.ui.components.SearchMatch
import com.labteto.dshmobile.ui.components.SearchMatches
import com.labteto.dshmobile.ui.components.SkillCard
import com.labteto.dshmobile.ui.components.SkillPhase
import com.labteto.dshmobile.ui.components.SubagentCard
import com.labteto.dshmobile.ui.components.SubagentPhase
import com.labteto.dshmobile.ui.components.TodoCard
import com.labteto.dshmobile.ui.components.TodoChange
import com.labteto.dshmobile.ui.components.TodoLine
import com.labteto.dshmobile.ui.components.TodoMark
import com.labteto.dshmobile.ui.components.ToolCard
import com.labteto.dshmobile.ui.components.ToolCardView
import com.labteto.dshmobile.ui.components.UserBubble
import com.labteto.dshmobile.ui.components.WebCardKind
import com.labteto.dshmobile.ui.components.WebSource
import com.labteto.dshmobile.ui.screens.main.SystemLine
import com.labteto.dshmobile.ui.screens.main.SystemLineRow
import com.labteto.dshmobile.ui.screens.main.ToolFailure
import com.labteto.dshmobile.ui.screens.main.ToolFailureLine
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.delay

/**
 * A debug page for `-DshToolCardsSample top|end`: the chat's shared tool cards (iOS M5b), every kind open, before any
 * iOS screen shows a transcript (that is M5c, which also wires the taps). The cards are the ones the demo harness's
 * tool-card session folds into (`DemoHarness.TOOL_CARDS_SESSION`), written as card models so the page needs no
 * harness. [at] `end` scrolls to the foot, so two screenshots cover the page. [onShown] gets
 * `DSH_TOOLCARDS_RESULT shown at=… cards=… height=…` once it is laid out.
 */
@Composable
internal fun ToolCardsSamplePage(at: String, onShown: (String) -> Unit) {
    DshTheme {
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .background(DsTheme.colors.bgBase)
                .safeDrawingPadding()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UserBubble("Write tests for the sync queue's retries.")
            for (card in CARDS) ToolCard(view = card.view, expanded = true, onToggle = {}, state = card.state)
            ToolCard(view = DENIED, expanded = false, onToggle = {}, state = DisclosureState.Error)
            ToolFailureLine(ToolFailure.Denied(PermissionDenial.Rejected, null))
            ToolCard(view = REVIEWED, expanded = false, onToggle = {}, state = DisclosureState.Error)
            ToolFailureLine(ToolFailure.Denied(PermissionDenial.AutoReviewRejected, "A force push can overwrite work someone else pushed."))
            ToolCard(view = FAILED_EDIT, expanded = false, onToggle = {}, state = DisclosureState.Error)
            ToolFailureLine(ToolFailure.Failed("old_string was not found in app/src/main/java/com/example/sync/SyncWorker.kt"))
            SystemLineRow(SystemLine.PermissionChanged("workspace-write"), presetNames = emptyMap())
            SystemLineRow(SystemLine.Ran("/review"), presetNames = emptyMap())
            ToolCard(view = RUNNING, expanded = true, onToggle = {}, state = DisclosureState.Running)
            ToolCard(view = STOPPED, expanded = false, onToggle = {}, state = DisclosureState.Stopped)
            ToolFailureLine(ToolFailure.Interrupted)
        }
        LaunchedEffect(Unit) {
            // Two frames for the layout and the fonts, then the scroll the argument asked for.
            delay(1_500)
            if (at == "end") scroll.scrollTo(scroll.maxValue)
            delay(500)
            onShown("DSH_TOOLCARDS_RESULT shown at=$at cards=${CARDS.size + 5} height=${scroll.maxValue + scroll.viewportSize}")
        }
    }
}

private class SampleCard(val view: ToolCardView, val state: DisclosureState = DisclosureState.Idle)

private const val QUEUE = "app/src/main/java/com/example/sync/SyncQueue.kt"

private val CARDS = listOf(
    SampleCard(
        TodoCard(
            done = 0, total = 3, active = "Read how the queue retries", activeExtra = 0,
            added = 1, updated = 1, removed = 0, initial = false, recorded = true,
            lines = listOf(
                TodoLine("Read how the queue retries", TodoMark.InProgress, TodoChange.StatusChanged, TodoMark.Pending),
                TodoLine("Run the sync tests", TodoMark.Pending, TodoChange.Added),
            ),
        ),
    ),
    SampleCard(
        ToolCardView.SearchCard(
            matches = SearchMatches.FileMatches(
                listOf(
                    SearchFile(QUEUE, listOf(SearchMatch(24, "    private val retryLimit = 3"), SearchMatch(41, "        if (attempt < retryLimit) retry(item)"))),
                    SearchFile("app/src/main/java/com/example/sync/SyncWorker.kt", listOf(SearchMatch(12, "    override fun shouldRetry() = queue.isNotEmpty()"))),
                ),
            ),
            total = 3,
        ),
    ),
    SampleCard(
        ToolCardView.SearchCard(
            matches = SearchMatches.PathList(listOf(QUEUE, "app/src/main/java/com/example/sync/SyncWorker.kt", "app/src/test/java/com/example/sync/SyncQueueTest.kt")),
            total = 3,
        ),
    ),
    SampleCard(
        ToolCardView.ReadCard(
            label = QUEUE,
            path = QUEUE,
            lines = listOf(
                "class SyncQueue(private val api: SyncApi) {",
                "    private val items = ArrayDeque<SyncItem>()",
                "",
                "    private val retryLimit = 3",
            ).mapIndexed { i, text -> ReadLine(20 + i, text) },
            totalLines = 88,
            lang = "kotlin",
        ),
    ),
    SampleCard(
        ToolCardView.WebCard(
            kind = WebCardKind.Search(
                answer = "Run the code under runTest and advance virtual time past each backoff.",
                sources = listOf(
                    WebSource("https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/", "kotlinx-coroutines-test", "Test utilities for code that uses coroutines."),
                    WebSource("https://developer.android.com/kotlin/coroutines/test", "Testing Kotlin coroutines on Android", "Use a TestDispatcher to control time."),
                ),
            ),
        ),
    ),
    SampleCard(ToolCardView.WebCard(kind = WebCardKind.Fetch("https://developer.android.com/kotlin/coroutines/test", 200))),
    SampleCard(
        ToolCardView.DiffCard(
            diffs = listOf(DiffHunk(QUEUE, "    private val retryLimit = 3\n", "    internal var retryLimit = 3\n        private set\n")),
        ),
    ),
    SampleCard(
        ToolCardView.TerminalCard(
            title = "./gradlew :app:testDebugUnitTest --tests '*SyncQueue*'",
            description = "Run the sync tests",
            cwd = "C:\\Users\\demo\\mobile-app",
            output = "SyncQueueTest > retries a failed upload three times PASSED\nSyncQueueTest > drops an item the server refused PASSED\n\nBUILD SUCCESSFUL in 38s",
            exitCode = 0,
            running = false,
        ),
    ),
    SampleCard(
        ToolCardView.TerminalCard(
            title = "Get-Content app/build/reports/lint.txt -Tail 3",
            description = "Show the lint summary",
            cwd = "C:\\Users\\demo\\mobile-app",
            output = "Get-Content: Cannot find path 'app/build/reports/lint.txt' because it does not exist.",
            exitCode = 1,
            running = false,
        ),
    ),
    SampleCard(
        SubagentCard(
            title = "Check the other workers for the same retry bug",
            prompt = "Look through app/src for workers that retry without a limit and list them.",
            model = null,
            phase = SubagentPhase.Started,
            childId = "child-07",
        ),
    ),
    SampleCard(SkillCard("android-testing", "How this project writes and runs unit tests", SkillPhase.Loaded)),
    SampleCard(
        ToolCardView.GenericCard(
            title = "plugin:notes/save-note",
            rawInput = """{"title":"Sync queue","body":"Retries stop after three attempts."}""",
            content = listOf(ContentBlockView.TextBlock("Saved note 12.")),
        ),
    ),
    SampleCard(
        DetailsCard(
            DetailsTool.JobList,
            listOf(
                DetailItem("./gradlew assembleDebug", technicalTitle = true, subtitle = "job-3", state = DetailState.Running,
                    fields = listOf(DetailField(DetailLabel.Kind, DetailValue.Text("bash", technical = true)))),
                DetailItem("./gradlew lint", technicalTitle = true, subtitle = "job-2", state = DetailState.Completed,
                    fields = listOf(DetailField(DetailLabel.Kind, DetailValue.Text("bash", technical = true)))),
            ),
        ),
    ),
    SampleCard(
        QuestionCard(
            listOf(QuestionLine("Should a refused upload be retried later?", "Retries", listOf("No, drop it", "Yes, once"), listOf("No, drop it"))),
            QuestionOutcome.Answered,
            answered = 1,
            total = 1,
        ),
    ),
)

private val DENIED = ToolCardView.TerminalCard(title = "rm -rf app/build", description = "Clean the build folder", running = false, failed = true)
private val REVIEWED = ToolCardView.TerminalCard(title = "git push --force", description = "Push the branch", running = false, failed = true)
private val FAILED_EDIT = ToolCardView.DiffCard(diffs = listOf(DiffHunk("app/src/main/java/com/example/sync/SyncWorker.kt", "retry(forever = true)", "retry(limit = 3)")))
private val RUNNING = ToolCardView.TerminalCard(title = "./gradlew assembleDebug", description = "Build the app", running = true)
private val STOPPED = ToolCardView.TerminalCard(title = "./gradlew :app:testDebugUnitTest", description = "Run all unit tests", running = false, stopped = true)
