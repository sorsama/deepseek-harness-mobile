# Compatibility

DSH Mobile speaks the DeepSeek Harness web-client protocol over `/api`. The protocol is not versioned on the wire, so the app pins the harness release its call shapes were checked against. `DshCore.PROTOCOL_BASELINE` is shown in Settings → About.

## Harness

| DSH Mobile | Harness | Notes |
|---|---|---|
| 0.19.0 | 0.2.2-rc.1 | All 15 real-host conformance scenarios pass on 0.2.2-rc.1, which changed live state frames, schedule edits, subagent chat addresses and image reads. Saving or deleting an automation needs a 0.2.2 host, because `schedule/edit` replaces `schedule/update` and `schedule/delete`. The in-app protocol baseline label still names the 0.2.1-rc.2 fixture until that fixture is regenerated |
| 0.18.0 | 0.2.1-rc.2 | Same protocol baseline as 0.17.0. Rewind to this message, from holding a sent message, needs dsh-rewind-plugin on the host |
| 0.17.0 | 0.2.1-rc.2 | All 15 real-host conformance scenarios pass on rc.2, including a typed round trip through `terminal/attach`. The terminal falls back to follow-and-call on hosts before 0.2.1-alpha.3 |
| 0.16.0 | 0.2.1-alpha.2 | All 14 real-host conformance scenarios passed across full and targeted runs; Android and signing gates in [validation](VALIDATION-0.16.0.md) |
| 0.15.0 | 0.2.1-alpha.1 | Same protocol baseline as 0.13.0 |
| 0.14.0 | 0.2.1-alpha.1 | Same protocol baseline as 0.13.0; the rewind picker needs dsh-rewind-plugin on the host |
| 0.13.0 | 0.2.1-alpha.1 | Protocol baseline; real-host conformance and pre-UX Android E2E passed, including an older-host check on 0.2.0-rc.1; see [validation](VALIDATION-0.13.0.md) for final UX verification status |
| 0.12.2 | 0.2.0-rc.1 | Nothing this client uses changed since 0.1.7-rc.2; 0.1.7-rc.x and 0.1.6-alpha.x hosts still work |
| 0.12.1 | 0.2.0-rc.1 | The agent preset chip never shows |
| 0.12.0 | 0.1.7-rc.2 | Still reads 0.1.6-alpha.x |
| 0.11.5 – 0.11.7 | 0.1.6-alpha.2 | Against 0.1.7, tool cards lose their results and presets, subagents, jobs, previews and archiving fail |
| 0.11.4 | 0.1.6-alpha.1 | Empty queue dock on later master; PTC dispatch rows in the transcript |
| 0.11.3 | 0.1.6-alpha.1 | Answered question cards stick |
| 0.11.1 – 0.11.2 | 0.1.6-alpha.1 | Answers and approvals refused by any harness ≥ 0.1.2 |
| 0.11.0 | 0.1.6-alpha.1 | See [validation](VALIDATION-0.11.0.md) |
| 0.10.0 – 0.10.1 | 0.1.3-alpha.1 | |
| 0.9.3 | 0.1.2-alpha.1 | No streaming on 0.1.3; commands refused |
| 0.9.0 – 0.9.2 | 0.1.2-alpha.1 | Cannot send messages |
| 0.6.0 – 0.8.0 | 0.1.1-rc.2 | |
| 0.5.0 | 0.1.0-rc.8 | |
| 0.4.0 | 0.1.0-rc.7 | |
| 0.1.0 – 0.3.1 | 0.1.0-rc.5 | |

0.11.x was built against master just past 0.1.6-alpha.1.

0.13.0 retains the existing wire fallbacks for 0.2.0-rc.1, 0.1.7-rc.x and
0.1.6-alpha.x. New management methods need host support. The tested 0.2.0-rc.1 host
returned 404 for `schedule/catalog`, so Automation was hidden from the drawer and
Details while sessions, chat and questions worked. Retaining the other fallbacks
does not establish a fresh test result for every older host; see
[0.13.0 validation](VALIDATION-0.13.0.md).

0.14.0 sends nothing new to the harness. Its rewind picker runs the dsh-rewind plugin's own
`/rewind` subcommands and reads their text output, so it depends on that plugin rather than on
the harness. It turns on only when the session's command catalog lists `rewind` next to the
plugin's `snapshot-auto-cleanup`. The output formats were checked against dsh-rewind-plugin
0.15.0-beta.1 and 0.15.1. The contributor drove the first version on a device against harness
0.2.0-rc.1 with 0.15.0-beta.1, and the review fixes were tested on an emulator against a scripted
host.

0.15.0 sends nothing new to the harness either. Before a preview offers open-on-host, it asks
`session/canOpenWorkspacePath`, which connection discovery already calls. When a workspace link's
name suggests the wrong kind (a file without an extension, a folder with a dot in its name), the
app corrects itself from the `workspace-file/not-directory` error of `workspaceFiles/list` and the
`workspace-file/not-regular-file` error with `kind: directory` from `workspaceFiles/stat`. Both
are in the harness source at 0.2.1-alpha.1. On a host that answered differently, the first guess
would stand and the app would show the error. The contributor checked the screens on a device
against their own host. The review fixes were tested on an emulator with device tests, not
against a live host.

- Do not interchange 0.10 and 0.9 on harness 0.1.3 and 0.1.2: live replies and command arguments changed. Upgrade both app and harness, or neither.
- 0.9.0 cannot speak the 0.1.1 protocol; the handshake fails.

## Relay

[`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay) is a separate versioned contract.

| DSH Mobile | dsh-relay | Notes |
|---|---|---|
| 0.11.0 – 0.12.2 | 0.2.1 | From 0.11.2 the `Host` header brackets an IPv6 literal; from 0.12.0 `readBytes` answers as multipart, which the relay passes through |
| 0.10.0 – 0.10.1 | 0.2.1 | Pairing payload and mDNS TXT `v: 1` |
| 0.9.1 – 0.9.3 | 0.2.1 | |
| 0.9.0 | 0.2.0 | |
| 0.8.0 | 0.1.1 | |

A relay older than 0.2.0 cannot serve harness ≥ 0.1.2: every proxied call returns 401 without the relay's own harness session. The `dsh-relay` 0.2.1 npm package is a [stale build](https://github.com/sorsama/deepseek-harness-relay/pull/6) that also answers 401; install from source with `dsh plugin --profile web add github:sorsama/deepseek-harness-relay#v0.2.1`. The app reports that 401 as the harness refusing the relay.

Pairing `kind` and `v` are checked and refused, rather than degraded; see [protocol notes](PROTOCOL.md).
0.13.0, 0.14.0 and 0.15.0 retain that relay contract; a live relay run is not yet recorded for any
of them.

## Conformance

The suite skips when no checkout is selected or discoverable. An explicitly selected missing or unbuilt checkout fails. CI builds the pinned commit and runs this suite:

```sh
DSH_HARNESS_SRC=/path/to/deepseek-harness ./gradlew :conformance:test
```

Against 0.2.1-alpha.1 it checks:

- launch-token exchange and browser session, plus unauthenticated 401 handling;
- the `$events` ready frame and argument names for the endpoints exercised by the suite;
- the inbox queue and the extra streams beyond `$events` and `session/control`;
- multipart `readBytes` answers byte for byte;
- a real tool-calling turn whose call and result pair;
- automation creation, calendar edits, optimistic conflicts, history and deletion;
- plugin inventory and management contracts, local bundle lifecycle, session references and absent late replies;
- a timed question that continues after its answer window closes and accepts a later answer.

Timed question ownership and draft behavior also have local regression coverage. See [0.13.0 validation](VALIDATION-0.13.0.md) for tested boundaries.

For the previous baseline, set `DSH_LEGACY_CONFORMANCE=true` alongside `DSH_HARNESS_SRC`;
this skips `Harness021ConformanceTest` and `TimedQuestionConformanceTest` while exercising
existing endpoints. Test definitions are not passing-run evidence; results are recorded
in the validation document.

## Version policy

- The app degrades on shape: unknown events and content pass through. A capability-unavailable
  or missing-method response hides the corresponding control. HTTP 403, authentication errors
  and transport failures remain visible and do not mark the capability unavailable.
- There is no version-shaped branch. Harness 0.1.2 removed `host.describe`, so nothing on the wire says which version is running.
- Baseline changes retain source-derived contract fixtures and run `:conformance` against the pinned source. `tools/capture` can additionally record wire traffic.

## Breaking changes by harness release

See [protocol notes](PROTOCOL.md) for the shapes.

### 0.2.2

Live projections are folded per committed event: one `session/control` `projection` frame carries
`changes:[{key,val}]` for every key the event touched, where through 0.2.1 a frame named a single
`key`/`value`. The app normalizes both, so model selection, permissions, todos, goal, plan, the
inbox queue, subagent catalog and agent preset continue to update live. `session/follow` gained
`activate`, which the app now sends so an opened session is promoted like the web client's follow;
the host never promotes a subagent child this way. `session/page`'s `throughSeq` became optional,
which changes nothing for the app (it still pins the follow cursor).

`session/attachment` now takes the whole durable image reference (`{sessionId, attachment}`
instead of `attachmentId`); the app sends it and keeps the id-only request only as a fallback for a
host that refuses the new form as invalid input.

The schedule management API was replaced: `schedule/update` and `schedule/delete` are gone and both
are one `schedule/edit` (`{id, action, patch?, expectedUpdatedAt?}`), the conflict check compares
`updatedAt` rather than a complete record, `schedule/list` is the host-wide paged list, and catalog
entries carry the destination as `target`, list `involvedSessionIds`, use `active|paused|ended`, and
include `kind:"rrule"` tasks. The app reads all of these; per-session Automations match the delivery
Session or any involved Session, the Inactive filter means "not active", and an RRULE task shows
its rule read-only rather than failing or crashing the editor. A 0.2.1 host keeps its old
`update`/`delete` methods, which this build no longer sends.

The `ask_user_question` intent union is open. 0.2.2 adds `kind:"approval"` (experimental CUA plugin);
the app decodes any unknown intent into an opaque value and renders the question's own options, so a
question batch is never dropped because of a presentation tag.

Child transcripts are opened with a subagent address, not a plain session address:
`{kind:"subagent", parentSessionId, childSessionId, mode}`. 0.2.2 hosts require it and
refuse a plain address with `subagent Sessions require their durable parent address`; the app
opens agent chats this way. A one-shot child is read-only, and a child whose descriptor has not
arrived yet (`unknown`) is read-only only until it does. A continuable child is messageable while
its exact parent Agent is live on the host (`subagents/prompt` answers `subagent/parent-unavailable`
otherwise).

### 0.2.1

New `schedule` management endpoints operate on Host storage records, separate from frozen legacy schedule events. Updates carry the complete observed `expected` record. `pluginManager` supports package operations and active install recovery by request ID. Timed questions use call IDs, claim streams and the durable `userQuestions` projection. A timeout permits continuation and never supplies an answer. Shipped presets remain blocking until the host enables timed mode.

### 0.2.0

Nothing this client uses changed. The new `productAnalytics` Remote is Desktop-only and unused here; scheduler failures now record synthetic error tool results (`ToolOutcomeUnknownError` or `ToolNotStartedError`), which the app folds like other error results.

### 0.1.7

Session format v4 moved tool-result call ids and error flags onto the message. Subagents moved from `subagents/list` to the `subagentCatalog` projection, jobs from `session/control` to `job/list`, and `readAll`/`readRelated` into multipart `readBytes`. Archiving a busy session is refused unless the request asks to stop its work. The app also reads the older 0.1.6 shapes.

### 0.1.3

Session format v2 removed durable assistant deltas and made the live assistant stream opt-in. Error codes became namespaced, commands take `submittedAttachments`, and file uploads were added.

### 0.1.2

`/api` moved to `namespace/method` and one `/api/remote.mux` socket. The `$events` ready frame replaced `host.describe`; answers use `$events/result` with `args`, and history uses `session/follow` plus `session/page`.

Every `/api` call needs a browser-session cookie obtained by exchanging the launch token at `GET /?token=`. A missing session returns 401, while a refused `Host`/`Origin` returns 403; behind a relay, the relay holds the harness session. The loopback-only `PRIVILEGED_METHODS` list was deleted, so a paired device can reach settings and credentials according to the relay's `privilegedMethods` policy.
