package com.labteto.dshmobile.core.session

/**
 * What this client does with each durable session-event type, stated once.
 *
 * The harness's own vocabulary is a generated list — `KNOWN_SESSION_EVENT_TYPES` in
 * `packages/core/session/src/known-event-types.ts` — and every member of it belongs to exactly one
 * of the three sets below. Nothing here is a guess about what a type *means*; it is a decision
 * about where it belongs on a phone screen, and the decision is written down so a reader can
 * disagree with it.
 *
 * Keeping the sets exhaustive is the point. Before this existed the fold had a literal skip list
 * that had drifted: it named `tool/code-dispatch`, a name the harness renamed to
 * `tool/ptc-dispatch` before this client's own pinned baseline, so PTC dispatch metadata fell
 * through to a raw JSON row in the middle of the transcript. A list nothing compares against is a
 * list that rots quietly, so `EventTypeClassificationTest` compares these three sets to the
 * harness checkout in both directions — a type the harness declares and we do not classify fails,
 * and so does a type we classify that the harness does not declare.
 *
 * None of this removes anything from the record. The fold's `journal` keeps every event verbatim
 * whatever set it lands in, and Trajectory reads the journal, so "log-only" means "not a chat row"
 * rather than "not shown".
 */
object EventClassification {

    /**
     * Types the fold has a dedicated arm for.
     *
     * Most produce a typed node. `assistant/attempt` deliberately produces none — it is a model
     * attempt that settled without a surface message — but it is folded rather than skipped,
     * because it closes the open attempt for its (turn, step).
     */
    val FOLDED: Set<String> = setOf(
        "turn/start",
        "turn/end",
        "user/message",
        "assistant/message",
        "assistant/attempt",
        "tool/call",
        "tool/result",
        "todo/write",
        "goal/change",
        "plan/mode",
        "compaction/start",
        "compaction/end",
        "compaction/prune",
        "compaction/summary",
        "llm/retry",
        "llm/retry-started",
        "command/run",
        "command/done",
        "session/title",
        "tool-workflow/run-start",
        "tool-workflow/run-end",
        "tool-workflow/agent-start",
        "tool-workflow/agent-end",
        "subagent/descriptor",
        // Harness 0.1.7: the agent's tool set changed. Folded to a context disclosure row.
        "developer/message",
    )

    /**
     * Types the fold drops: bookkeeping the transcript does not carry.
     *
     * Each is already surfaced somewhere better — an approval by its card, a permission by the
     * picker, feedback by its own rating control, a catalog by its projection — so repeating it as
     * a chat row would be noise rather than information.
     */
    val LOG_ONLY: Set<String> = setOf(
        "session/end-seed",
        "approval/asked",
        "approval/decided",
        "approval/policy",
        "permission/preset",
        "sandbox/mode",
        "schedule/change",
        "feedback/record",
        "feedback/message-put",
        "feedback/message-delete",
        "hook/invoked",
        "hook/result",
        "agent-preset/selected",
        "agent/inbox/spliced",
        "model/selection",
        "request/header",
        "session-log-deepseek/delivery-accepted",
        "subagent/catalog",
        "subagent/model-selection-policy",
        // Renamed upstream from `tool/code-dispatch*` before this client's pinned baseline; the
        // old spelling survives only inside the harness's v0-to-v1 migration.
        "tool/ptc-dispatch",
        "tool/ptc-dispatch-start",
        "web/deepseek-search-llm-request",
        "session/title-llm-request",
        // Current directory is surfaced by the workingDirectory projection in session details.
        "working-directory/change",
        // Harness 0.2.1-alpha.3: every background bash/pwsh/subagent/workflow job is recorded when it
        // starts and when it settles. The tool card that started it and the Details jobs list
        // already carry both facts; as rows they were raw JSON after every background command.
        "job/registered",
        "job/settled",
    )

    /**
     * Types the fold deliberately passes through as an untyped row, leaving presentation to decide.
     *
     * This is the same path an event type this build has never heard of takes, which is the
     * compatibility contract: a newer harness's events stay visible rather than vanishing. The
     * difference is that these are *known* to arrive, so the app can label them
     * (`system/message`, `request/context`, `image/offload`), render them properly
     * (`deliverables/presented`) or hide them as structural noise (`step/start`, `step/end`) —
     * see `STRUCTURAL_EVENT_TYPES` in the app's `ChatNodeItem`.
     */
    val PASSTHROUGH: Set<String> = setOf(
        "step/start",
        "step/end",
        "system/message",
        "request/context",
        "image/offload",
        "deliverables/presented",
        "workspace/changes",
        "team/member",
        "team/task",
        "team/message/queued",
        "team/message/delivered",
    )

    /**
     * Folded, but not part of the harness's current vocabulary.
     *
     * `assistant/chunk` was a durable event through harness 0.1.2 and became a process-local
     * stream frame in 0.1.3. The fold still reads it so a 0.1.2 host's log renders, which is why
     * it is folded without appearing in the harness's generated list.
     */
    val FOLDED_LEGACY: Set<String> = setOf("assistant/chunk")

    /** Every type this build has an opinion about, legacy included. */
    val CLASSIFIED: Set<String> = FOLDED + LOG_ONLY + PASSTHROUGH + FOLDED_LEGACY
}
