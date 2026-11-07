package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class QuestionWait(val callId: String, val timed: Boolean = false)

@Serializable
data class QuestionWaitFrame(val remainingMs: Long)

@Serializable
data class ContinuedQuestion(val callId: String, val questions: List<AskUserQuestionItem>, val state: String)

@Serializable
data class SettledQuestion(val callId: String, val answers: List<AskUserQuestionAnswerItem> = emptyList())

@Serializable
data class UserQuestionsView(
    val active: List<ContinuedQuestion> = emptyList(),
    val settled: List<SettledQuestion> = emptyList(),
)

/**
 * One host storage record as `schedule/catalog` returns it, distinct from the frozen v1
 * `schedule/change` event vocabulary.
 *
 * Harness 0.2.2 spread the record into a `ScheduleCatalogEntry`: the rule fields are unchanged, the
 * delivery destination moved from a top-level `sessionId` into [target], the status vocabulary is
 * `active|paused|ended` (0.2.1 sent `active|inactive`), and `kind:"rrule"` was added. The legacy
 * `sessionId` stays decoded so a 0.2.1 host remains readable.
 */
@Serializable
data class AutomationTask(
    val id: String,
    val kind: String,
    val title: String,
    val prompt: String,
    val scheduledAt: String,
    val sessionId: String = "",
    val status: String = "active",
    val afterSeconds: Long? = null,
    val everySeconds: Long? = null,
    val time: String? = null,
    val timeZone: String? = null,
    val weekdays: List<Int>? = null,
    val expression: String? = null,
    val lastDelivery: DeliveryRecord? = null,
    /** 0.2.2: one normalized DTSTART line and one RRULE line; present only for `kind:"rrule"`. */
    val rrule: String? = null,
    /** 0.2.2: stored delivery destination, `{kind:"session",sessionId}` or `{kind:"new_once"|"new",...}`. */
    val target: JsonObject? = null,
    /** 0.2.2: creating/editing Sessions plus each existing or reserved target Session; `new` targets excluded. */
    val involvedSessionIds: List<String> = emptyList(),
    val createdAt: String? = null,
    /** 0.2.2: latest stored change; editors send it back as `expectedUpdatedAt`. */
    val updatedAt: String? = null,
) {
    /**
     * The Session this task delivers to, under either vocabulary.
     *
     * 0.2.2 moved the binding into [target] (`sessionId` for `session`/`new_once`; a `new` target
     * creates a fresh Session per delivery and names none), so the legacy top-level `sessionId` is
     * only a fallback for 0.2.1 hosts.
     */
    val sessionIdEffective: String?
        get() = (target?.get("sessionId") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: sessionId.takeIf { it.isNotEmpty() }
}

@Serializable
data class DeliveryRecord(val scheduledAt: String, val deliveredAt: String, val messageId: String, val prompt: String? = null)

@Serializable
data class AutomationHistory(
    val id: String,
    val records: List<DeliveryRecord> = emptyList(),
    val nextBefore: String? = null,
    val earlierRecordsUnavailable: Boolean = false,
    val earlierRecordsPruned: Boolean = false,
    val retention: JsonObject? = null,
    val code: String? = null,
)

/**
 * One closed `schedule/edit` operation (harness 0.2.2 replaced `schedule/update` and
 * `schedule/delete` with this: `action "update"` carries [patch], `action "delete"` must not).
 */
@Serializable
data class AutomationEditRequest(
    val id: String,
    val action: String,
    val patch: AutomationPatch? = null,
    val expectedUpdatedAt: String? = null,
)

/** Fields one update changes atomically; the host keeps every omitted field (harness 0.2.2 `SchedulePatch`). */
@Serializable
data class AutomationPatch(
    val title: String? = null,
    val prompt: String? = null,
    /** A `ScheduleTiming` object; [com.labteto.dshmobile.core.wire.automationTiming] builds the shapes the app edits. */
    val timing: JsonObject? = null,
    val target: JsonObject? = null,
    val active: Boolean? = null,
)

/**
 * One `schedule/edit` outcome (harness 0.2.2 `ScheduleEditResult`): a successful update answers with
 * the whole catalog entry (`id` plus fields, no `record`), a successful delete with
 * `{id,deleted:true}`, and a miss/conflict with `{id,code}`. The entry-shaped fields other than [id]
 * are irrelevant to the app, which refreshes the catalog instead; [record] keeps decoding a 0.2.1
 * host's `{id,updated,record}` result.
 */
@Serializable
data class AutomationMutation(
    val id: String? = null,
    val updated: Boolean? = null,
    val deleted: Boolean? = null,
    val record: AutomationTask? = null,
    val code: String? = null,
    val message: String? = null,
)

/** One page of the host-wide task list (harness 0.2.2 `schedule/list`; the per-Session request is gone). */
@Serializable
data class ScheduleListResult(
    val items: List<AutomationTask> = emptyList(),
    val shown: Int = 0,
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class PluginManagementError(val code: String, val diagnostic: String? = null, val incompatible: List<JsonObject> = emptyList())

@Serializable
data class PluginBundleRow(val rowId: String, val moduleName: String, val entryId: String? = null)

@Serializable
data class PluginBundle(
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val enabled: Boolean,
    val installed: Boolean,
    val optional: Boolean = false,
    val removable: Boolean = false,
    val source: String? = null,
    val readOnlyReason: String? = null,
    val error: PluginManagementError? = null,
    val rows: List<PluginBundleRow> = emptyList(),
    val overrides: List<String> = emptyList(),
    val official: Boolean = false,
    val availability: String? = null,
    val installTarget: PluginInstallTarget? = null,
)

@Serializable
data class PluginInstallTarget(val spec: String, val version: String)

@Serializable
data class ManagedPlugin(
    val entryId: String,
    val moduleName: String,
    val enabled: Boolean,
    val patchId: String? = null,
    val readOnlyReason: String? = null,
)

@Serializable
data class PluginRegistries(val registry: String? = null, val fallbackRegistries: List<String> = emptyList(), val resolved: String? = null)

@Serializable
data class PluginInspection(
    val status: String,
    val kind: String? = null,
    val name: String? = null,
    val version: String? = null,
    val description: String? = null,
    val bundle: Boolean? = null,
    val registry: String? = null,
    val host: String? = null,
    val problem: String? = null,
    val reason: String? = null,
)

@Serializable
data class PluginInstallOptions(
    val enabled: Boolean = true,
    val requestId: String,
    val approvedBuilds: List<String>? = null,
    val registry: String? = null,
    val saveExact: Boolean? = null,
)

@Serializable
data class PluginPackageResult(
    val exitCode: Int,
    val output: String,
    val truncated: Boolean = false,
    val logPath: String = "",
    val kind: String? = null,
    val timedOut: Boolean = false,
)

@Serializable
data class PluginChangeResult(
    val changed: Boolean,
    val application: String,
    val stage: String,
    val target: String,
    val enabled: Boolean? = null,
    val error: PluginManagementError? = null,
    val warnings: List<String> = emptyList(),
    val packageResult: PluginPackageResult? = null,
    val bundle: String? = null,
    val version: String? = null,
    val pendingBuilds: List<String> = emptyList(),
    val approvedBuilds: List<String> = emptyList(),
    val failedAt: String? = null,
)

@Serializable
data class PluginInstallCancellation(val status: String)

@Serializable
data class PluginInstallProgress(val requestId: String, val phase: String, val attempt: JsonObject? = null)

@Serializable
data class PluginInstallLog(val requestId: String? = null, val jobId: String, val text: String, val stream: String)

@Serializable
data class ReferenceCandidate(val label: String, val mention: String, val kind: String? = null, val sessionId: String? = null, val displayTitle: String? = null)
