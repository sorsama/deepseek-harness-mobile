package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.*
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.inkFieldFrame
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.*

/**
 * Thumbs up/down on one message: loads the current rating when there is one, writes or removes it,
 * and keeps a conflict recoverable by holding the host's returned version.
 *
 * Ink treatment: a dialog plate rather than a platform alert, the same field styling as the rest
 * of the app, and explicit save/cancel/remove actions.
 */
@Composable
internal fun FeedbackDialog(store: SessionStore, key: ComposerKey, messageId: String, positive: Boolean, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var note by remember(key, messageId) { mutableStateOf("") }
    var current by remember(key, messageId) { mutableStateOf<MessageFeedbackItem?>(null) }
    var busy by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    // What went wrong, as a kind rather than the host's raw code: the reader is told in words, and a
    // code such as `version-conflict` or an empty string never reaches the screen.
    var problem by remember { mutableStateOf<FeedbackProblem?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        val result = store.apiForHost(key.host)?.messageFeedbackList(key.sessionId)?.requireValue()
            ?: throw FeedbackOfflineException()
        if (!result.ok) throw FeedbackRejectedException(result.error?.code)
        current = result.value?.items?.firstOrNull { it.messageId == messageId }
        note = current?.note.orEmpty()
        loaded = true
    }
    fun submit(remove: Boolean) {
        if (busy || !loaded) return
        busy = true; problem = null
        scope.launch {
            try {
                val api = store.apiForHost(key.host) ?: throw FeedbackOfflineException()
                val failure = if (remove) {
                    val version = current?.version ?: return@launch
                    val result = api.messageFeedbackDelete(MessageFeedbackDeleteRequest(key.sessionId, messageId, version)).requireValue()
                    if (result.ok) null else result.error ?: MessageFeedbackFailure("unknown")
                } else {
                    val result = api.messageFeedbackPut(MessageFeedbackPutRequest(key.sessionId, messageId,
                        if (positive) "positive" else "negative", current?.version, note.takeIf { it.isNotBlank() })).requireValue()
                    if (result.ok) null else result.error ?: MessageFeedbackFailure("unknown")
                }
                if (failure != null) {
                    if (failure.code == "version-conflict") current = failure.current
                    problem = feedbackProblem(failure.code)
                    haptics.reject()
                } else {
                    haptics.confirm()
                    onDismiss()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem = feedbackProblemOf(e); haptics.reject() }
            finally { busy = false }
        }
    }
    LaunchedEffect(key, messageId) {
        try { refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { problem = feedbackProblemOf(e) }
        finally { busy = false }
    }
    // Dismissal is never locked. The dialog used to ignore back, a tap outside and Cancel while the
    // rating loaded, so a stalled host held the reader inside it. Leaving drops the composition,
    // which cancels the load or save that was in flight along with it.
    DsDialog(
        title = stringResource(if (positive) R.string.chat_feedback_up else R.string.chat_feedback_down),
        onDismiss = onDismiss,
    ) {
        TextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth().inkFieldFrame(),
            enabled = !busy,
            label = { Text(stringResource(R.string.feedback_note), style = DsType.caption11) },
            textStyle = DsType.std14,
            colors = dialogTextFieldColors(),
            shape = DsShapes.control,
        )
        problem?.let { Text(stringResource(it.messageRes), color = colors.error, style = DsType.small13) }
        if (!loaded && !busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
                DsButton(
                    text = stringResource(R.string.common_retry),
                    onClick = {
                        busy = true
                        scope.launch {
                            try { refresh(); problem = null }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { problem = feedbackProblemOf(e) }
                            finally { busy = false }
                        }
                    },
                    variant = DsButtonVariant.Outline,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            if (current != null) {
                DsButton(
                    text = stringResource(R.string.common_remove),
                    onClick = { submit(true) },
                    enabled = !busy,
                    variant = DsButtonVariant.Outline,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DsButton(
                text = stringResource(R.string.feedback_send),
                onClick = { submit(false) },
                enabled = !busy && loaded,
                variant = DsButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Why a feedback call did not go through, in terms the reader can act on. */
internal enum class FeedbackProblem(val messageRes: Int) {
    /** The rating was changed somewhere else while this dialog was open. */
    Conflict(R.string.feedback_error_conflict),

    /** There is no connection to the harness right now. */
    Offline(R.string.common_offline),

    /** Anything else: the host refused or the call failed in a way there is no better word for. */
    Generic(R.string.feedback_error_generic),
}

/** The host's failure code as a [FeedbackProblem]; only a version conflict has a code worth naming. */
internal fun feedbackProblem(code: String?): FeedbackProblem =
    if (code == "version-conflict") FeedbackProblem.Conflict else FeedbackProblem.Generic

internal class FeedbackOfflineException : Exception()

internal class FeedbackRejectedException(val code: String?) : Exception(code)

/** What a thrown failure means to the reader: no connection, a named refusal, or just "it failed". */
internal fun feedbackProblemOf(failure: Throwable): FeedbackProblem = when (failure) {
    is FeedbackOfflineException -> FeedbackProblem.Offline
    is FeedbackRejectedException -> feedbackProblem(failure.code)
    else -> FeedbackProblem.Generic
}
