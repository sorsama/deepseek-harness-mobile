package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.JobFollowFrame
import com.labteto.dshmobile.core.wire.dto.JobStatus
import com.labteto.dshmobile.core.wire.dto.JobView
import com.labteto.dshmobile.data.ActionOutcome
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.data.failureNotice
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Most characters of output the sheet keeps. A job's ring is the host's to retain; a phone showing
 * a long build log only needs its tail, and an unbounded string grows with every frame.
 */
private const val MAX_OUTPUT_CHARS = 256 * 1024

/**
 * One background job's output, followed live (harness 0.1.7's `job/follow`).
 *
 * The stream starts at the oldest byte the host still holds, delivers output in batches, and ends
 * with the job's settled state once it has finished and drained. Output the host already evicted,
 * or that this sheet trims to stay bounded, is marked rather than silently missing.
 *
 * Ink treatment: the job's state is a tag rather than a sentence, the elapsed time ticks while the
 * job runs (reading the clock once would freeze it), and the log sits on the code-block surface in
 * the mono face, tail-following the way a terminal does.
 */
@Composable
internal fun JobOutputSheet(job: JobView, store: SessionStore, canStop: Boolean, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    var current by remember(job.id) { mutableStateOf(job) }
    // The log as lines, appended in place: see [JobLog] for why it is not one growing string.
    val lines = remember(job.id) { mutableStateListOf<String>() }
    val log = remember(job.id) { JobLog(lines, MAX_OUTPUT_CHARS) }
    // The host evicted some output before the stream began, or marked a gap in it. The log's own
    // trimming is reported separately, by [JobLog.dropped].
    var hostDropped by remember(job.id) { mutableStateOf(false) }
    var ended by remember(job.id) { mutableStateOf(false) }
    var stopProblem by remember(job.id) { mutableStateOf<String?>(null) }
    val stopFailedLabel = stringResource(R.string.details_failed_stop_job)

    LaunchedEffect(job.id) {
        try {
            store.followJob(job.id).collect { frame ->
                when (frame) {
                    is JobFollowFrame.Opened -> {
                        current = frame.job
                        // The stream begins at the oldest retained byte; past zero, the head is gone.
                        if (frame.from > 0) hostDropped = true
                    }
                    is JobFollowFrame.Output -> {
                        if (frame.lossy || frame.chunks.any { it.gapBefore }) hostDropped = true
                        log.append(frame.chunks.joinToString("") { it.text })
                    }
                    is JobFollowFrame.Status -> current = frame.job
                    is JobFollowFrame.Unknown -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The rows still show the job's state; losing the live feed is not worth a banner.
        }
        ended = true
    }

    val running = current.status == JobStatus.RUNNING || current.status == JobStatus.STOPPING
    // Elapsed time has to be driven by a ticker; reading the clock inside a composable that never
    // recomposes leaves the timer frozen at its first value.
    var now by remember(job.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = formatDurationMs((current.finishedAt ?: now) - current.startedAt)

    // The log is a reversed list, newest line at index 0 and pinned to the bottom, which is what lets
    // output stream in without moving the lines under the reader's eyes (the same reasoning as the
    // chat transcript). Following the tail is the reader's intent, not the list's position: the
    // list is at rest at the tail when they have not scrolled away from it.
    val listState = rememberLazyListState()
    var follow by remember(job.id) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = listState.atLogTail()
        }
    }
    // New output lands below the viewport in a reversed list; pin it back to the tail only while the
    // reader is there. Away from the tail nothing moves, so an earlier error can be read while the
    // build goes on.
    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.requestScrollToItem(0)
    }
    val away by remember(listState) {
        derivedStateOf { !listState.atLogTail() }
    }

    DsBottomSheet(
        title = current.label,
        subtitle = listOfNotNull(current.kind, jobStatusLabel(current.status), current.progress ?: current.detail)
            .joinToString(" · "),
        onDismiss = onDismiss,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canStop && current.status == JobStatus.RUNNING) {
                    DsIconButton(
                        icon = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.jobs_stop),
                        onClick = {
                            scope.launch {
                                // The sheet sits over the chat's banner, so a refusal is said here.
                                val outcome = store.killJob(current.id, report = false)
                                stopProblem = (outcome as? ActionOutcome.Failed)?.let { failureNotice(stopFailedLabel, it.message) }
                            }
                        },
                        tint = colors.error,
                    )
                }
                DsIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.common_close),
                    onClick = onDismiss,
                )
            }
        },
    ) {
        // State strip: dot, state in words, how long it has been going.
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(jobStatusDot(current.status))
            Spacer(Modifier.width(DsSpacing.small))
            Column(Modifier.weight(1f)) {
                Text(
                    jobStateText(current.status),
                    style = DsType.small13Strong,
                    color = colors.labelPrimary,
                )
                Text(
                    elapsed,
                    style = DsType.mono12,
                    color = colors.labelTertiary,
                )
            }
            jobStateTag(current.status)
        }
        stopProblem?.let { Text(it, style = DsType.small13, color = colors.error) }
        // Read here, inside the content, so a batch of output (which changes the list) and the log
        // being trimmed (which does not) are both seen on the pass that follows them.
        val lineCount = lines.size
        val removed = log.removedLines
        if (hostDropped || log.dropped) {
            Text(
                stringResource(R.string.jobs_output_dropped),
                style = DsType.caption11,
                color = colors.warnLabel,
            )
        }
        Text(
            stringResource(R.string.ink_files_output),
            style = DsType.label12,
            color = colors.labelTertiary,
            modifier = Modifier.padding(top = DsSpacing.tiny),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 420.dp)
                .clip(DsShapes.code)
                .background(colors.codeBlockBg)
                .border(1.dp, colors.borderL1, DsShapes.code),
        ) {
            when {
                lineCount > 0 -> {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(DsSpacing.medium),
                            reverseLayout = true,
                        ) {
                            // Newest first. A line's key is its place in the whole log, so cutting
                            // the head to stay bounded does not make the rows below it look new.
                            items(count = lineCount, key = { index -> removed + (lineCount - 1 - index) }) { index ->
                                Text(
                                    lines.getOrNull(lineCount - 1 - index).orEmpty(),
                                    style = DsType.mono12,
                                    color = colors.labelPrimary,
                                    softWrap = true,
                                )
                            }
                        }
                    }
                    // Away from the tail the log stops following; one tap puts the reader back.
                    // Fully qualified: inside the sheet's column, the bare name resolves to the
                    // ColumnScope overload, which cannot be called from a Box.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = away,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(DsSpacing.small),
                        enter = fadeIn(DsAnimations.fade),
                        exit = fadeOut(DsAnimations.fade),
                    ) {
                        LatestPill(
                            onClick = {
                                follow = true
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                        )
                    }
                }
                !ended -> Box(Modifier.padding(DsSpacing.medium)) { OutputSkeleton() }
                else -> Text(
                    stringResource(R.string.jobs_output_empty),
                    style = DsType.small13,
                    color = colors.labelTertiary,
                    modifier = Modifier.padding(DsSpacing.medium),
                )
            }
        }
    }
}

/** True while the log shows its newest line: the reversed list is at rest on index 0. */
private fun LazyListState.atLogTail(): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/** "Jump to latest", for a reader who scrolled up to read something while the job went on. */
@Composable
private fun LatestPill(onClick: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val label = stringResource(R.string.chat_jump_latest)
    Surface(
        onClick = {
            haptics.tap()
            onClick()
        },
        shape = DsShapes.pillFull,
        color = colors.bgLayer2,
        border = BorderStroke(1.dp, colors.borderL2),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = DsSpacing.touchTarget)
                .padding(horizontal = DsSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Text(label, style = DsType.small13Strong, color = colors.labelPrimary, maxLines = 1)
            Icon(
                Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The job's state in words; falls back to a neutral word for a state this build cannot name. */
@Composable
private fun jobStateText(status: JobStatus): String =
    jobStatusLabel(status) ?: stringResource(R.string.common_unavailable)

/** State tag: green while running/settled cleanly, red when it failed, grey when it was killed. */
@Composable
private fun jobStateTag(status: JobStatus) {
    val colors = DsTheme.colors
    val (label, foreground, background, border) = when (status) {
        JobStatus.RUNNING -> JobTag(
            stringResource(R.string.jobs_running), colors.success, colors.successTertiary, colors.successBorder,
        )
        JobStatus.STOPPING -> JobTag(
            stringResource(R.string.jobs_stopping), colors.warnLabel, colors.warnTertiary, colors.warnBorder,
        )
        JobStatus.COMPLETED -> JobTag(
            stringResource(R.string.jobs_completed), colors.success, colors.successTertiary, colors.successBorder,
        )
        JobStatus.FAILED -> JobTag(
            stringResource(R.string.jobs_failed), colors.error, colors.errorTertiary, colors.errorBorder,
        )
        JobStatus.KILLED -> JobTag(
            stringResource(R.string.jobs_killed), colors.labelSecondary, colors.bgLayer3, colors.borderL2,
        )
        JobStatus.UNKNOWN -> JobTag(
            stringResource(R.string.common_unavailable), colors.labelTertiary, colors.bgLayer3, colors.borderL2,
        )
    }
    Text(
        label,
        style = DsType.caption11Strong,
        color = foreground,
        maxLines = 1,
        modifier = Modifier
            .clip(DsShapes.chip)
            .background(background)
            .border(1.dp, border, DsShapes.chip)
            .padding(horizontal = DsSpacing.xsmall, vertical = 2.dp),
    )
}

private data class JobTag(
    val label: String,
    val foreground: androidx.compose.ui.graphics.Color,
    val background: androidx.compose.ui.graphics.Color,
    val border: androidx.compose.ui.graphics.Color,
)

/** Placeholder log lines while the first frame is still on the wire. */
@Composable
private fun OutputSkeleton() {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        repeat(5) { index ->
            Box(
                Modifier
                    .fillMaxWidth(if (index % 2 == 0) 0.72f else 0.45f)
                    .height(10.dp)
                    .skeleton(colors.bgLayer2, colors.borderL2, DsShapes.chip),
            )
        }
    }
}
