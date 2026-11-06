package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.automationHistory
import com.labteto.dshmobile.core.wire.dto.AutomationHistory
import com.labteto.dshmobile.core.wire.dto.AutomationTask
import com.labteto.dshmobile.core.wire.dto.DeliveryRecord
import com.labteto.dshmobile.core.wire.featureValue
import com.labteto.dshmobile.data.HarnessFeature
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Mounted only on the Records tab; leaving it cancels outstanding page requests. */
@Composable
internal fun AutomationRecords(store: SessionStore, host: String?, task: AutomationTask) {
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    var history by remember { mutableStateOf<AutomationHistory?>(null) }
    var records by remember { mutableStateOf<List<DeliveryRecord>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var diagnostic by remember { mutableStateOf<String?>(null) }
    var retryBefore by remember { mutableStateOf<String?>(null) }
    val offline = stringResource(R.string.common_offline)
    val loadError = stringResource(R.string.harness_automation_history_error)
    val notFound = stringResource(R.string.harness_automation_history_not_found)
    val cursorError = stringResource(R.string.harness_automation_history_cursor_error)

    suspend fun load(before: String? = null) {
        if (busy) return
        busy = true
        error = null
        diagnostic = null
        retryBefore = before
        try {
            val api = store.apiForHost(host) ?: error(offline)
            val page = store.featureCall(HarnessFeature.AUTOMATION, api) {
                // 0.2.2 addresses saved deliveries by task id alone.
                api.automationHistory(task.id, before)
            }.featureValue()
            if (host != store.activeHostKey) return
            if (page.code != null) {
                // An expired cursor must restart pagination rather than repeat the invalid cursor.
                retryBefore = null
                error = when (page.code) {
                    "schedule_not_found" -> notFound
                    "delivery_cursor_not_found" -> cursorError
                    else -> loadError
                }
                diagnostic = page.code
            } else {
                records = (if (before == null) page.records else records + page.records).distinctBy { it.messageId }
                history = page
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = loadError
            diagnostic = e.message
        } finally {
            busy = false
        }
    }

    LaunchedEffect(host, task.id) { load() }
    val formatter = scheduleFormatter()
    val locale = LocalConfiguration.current.locales[0]
    if (busy) FeatureProgress()
    error?.let { message ->
        DsCard {
            Text(message, color = colors.error, style = DsType.std14)
            FeatureAction(stringResource(if (message == cursorError) R.string.ux_a_refresh else R.string.common_retry), !busy) {
                scope.launch { load(retryBefore) }
            }
            FeatureDiagnostics(diagnostic)
        }
    }
    if (!busy && error == null && history != null && records.isEmpty()) {
        EmptyHero(stringResource(R.string.harness_automation_history_empty), null, showPreview = false)
    }
    SectionLabel(stringResource(R.string.harness_automation_records))
    records.forEach { record ->
        DsCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(DsShapes.buttonSmall)
                        .background(colors.bgLayer3),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(15.dp), tint = colors.accent)
                }
                Spacer(Modifier.width(DsSpacing.small))
                Column(Modifier.weight(1f)) {
                    Text(formatter.absolute(record.scheduledAt), style = DsType.small13Strong, color = colors.labelPrimary)
                    Text(
                        formatter.absolute(record.deliveredAt),
                        style = DsType.mono12,
                        color = colors.labelTertiary,
                    )
                }
            }
            record.prompt?.let { Text(it, style = DsType.std14, color = colors.labelSecondary, maxLines = 4) }
        }
    }
    history?.let { page ->
        page.nextBefore?.let { before ->
            if (error == null) FeatureAction(stringResource(R.string.harness_more), !busy) {
                scope.launch { load(before) }
            }
        }
        val days = (page.retention?.get("days") as? JsonPrimitive)?.intOrNull
        val count = (page.retention?.get("records") as? JsonPrimitive)?.intOrNull
        if (days != null && count != null) {
            Text(
                stringResource(R.string.ux_a_retention, formatter.amount(days.toLong(), ScheduleUnit.DAY),
                    pluralStringResource(R.plurals.ux_a_records, count, java.text.NumberFormat.getIntegerInstance(locale).format(count))),
                style = DsType.caption11, color = colors.labelTertiary,
            )
        }
        if (!busy && error == null && page.nextBefore == null) {
            if (page.earlierRecordsUnavailable) Text(stringResource(R.string.harness_automation_history_unavailable), style = DsType.caption11, color = colors.labelTertiary)
            if (page.earlierRecordsPruned) Text(stringResource(R.string.harness_automation_history_pruned), style = DsType.caption11, color = colors.labelTertiary)
        }
    }
}
