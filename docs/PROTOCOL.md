# Protocol notes

What DSH Mobile speaks, in one page. Authoritative shapes live in the harness
repository — `packages/api/*/src/types.ts`,
`packages/api/gateway/src/stream-protocol.ts`, `packages/llm/llm/src/assistant-stream.ts`
and `packages/client/file-upload/src/*` — and this document records the
subset the app implements, against harness **0.1.3-alpha.1**.

## Envelopes

All JSON. `rpcId` is a UUID minted by the initiator and echoed.

### Client → server (HTTP POST)

`POST /api/<namespace>/<method>` — every unary call. There is one form now;
the flat `POST /api/<method>` vocabulary (`session.list`, `host.describe`, …)
and `POST /api/respond` are both gone.

`{"args": …}` is the **payload**, not the body, and the envelope's `method` must
equal the path.

```json
{"type":"client-request","rpcId":"<uuid>","method":"commands/list",
 "payload":{"args":{"agentId":"<sessionId>"}}}
```

Args are a named object whose keys are the **host method's own parameter names**,
matched exactly — a missing key is refused as readily as an unexpected one. Two
consequences worth stating, because neither is a naming convention this client
chose:

- A parameter whose type is a lookup (an `Agent`) is named `<key>Id` on the wire,
  which is why a session-addressed method takes `agentId`.
- Several calls take flat arguments rather than a request object:
  `settings/update` takes `{ns, patch, expectedRevision}`, `credentials/set`
  takes `{ref, value}`, `agentPresets/copy` takes `{from, id, name?}`,
  `commands/execute` takes `{agentId, line, submittedAttachments}`. And
  `session/list`'s sole argument is genuinely named `_request` — the host method
  ignores it, but the wire name is the parameter identifier verbatim.

Key matching is only the first of two checks. An endpoint whose sole argument is
`request` then decodes that object against a strict codec, which refuses a
missing required field *after* the args themselves passed — and says so
differently: `wire field "request" failed boundary validation`, under code
`gateway/input-invalid` (a bare `internal` through 0.1.2). Both prompt
endpoints turn on a field this client did not always send: `session/prompt` and
`subagents/prompt` require a `requestId`, minted by the sender, one fresh id per
human message, which the host persists on the message the prompt is accepted as.
Omit it and every send fails while every other call still works.

The namespaces this app uses: `session`, `workspace`, `directoryPicker`,
`settings`, `credentials`, `llm`, `skills`, `subagents`, `agentPresets`, `goals`,
`commands`, `fileReferences`, `pluginInventory`, `fileUploads`.

A path no gateway claims answers **404**; the `Host`/`Origin` fence answers
**403**; a caller with no browser session gets **401**. None of the three is a
broken connection, so the client maps them to `capability-unavailable`,
`forbidden` and `unauthenticated` and either hides the feature or asks the user
to pair, rather than reporting a transport failure.

### Error codes

Every business code is namespaced since 0.1.3, `<service>/<fault>`. The ones
this client reads:

| Code | Was (0.1.2) | Meaning |
|---|---|---|
| `session/attachment-invalid` | `attachment-error` | a prompt's or command's attachments were refused; `details.reason` names the bound (`IMAGE_TOO_LARGE`, `FILE_NOT_STAGED`, `SUBAGENT_FILE_UNSUPPORTED`, …) |
| `session/agent-busy` | `agent-busy` | the agent cannot take the request now |
| `session/not-found` | `session-not-found` | no such session |
| `gateway/arguments-invalid` | `internal` | the args object did not match the descriptor |
| `gateway/input-invalid` | `internal` | a `request` object failed its codec |
| `gateway/bad-request`, `gateway/internal`, `gateway/cancelled` | `internal` | the gateway's own failures |

The four the client mints itself — `capability-unavailable`, `unauthenticated`,
`forbidden`, `internal` — describe the carrier and are unchanged.

`commands/execute` is the **only** command write path. `session/prompt` does not
inspect its content — a leading-slash prompt reaches the model as ordinary user
text — so the client adjudicates the draft against `commands/list` before sending
and only calls `session/prompt` when the line names no registered command. That
miss is load-bearing: a `/name` line the catalog does not claim is how a *skill*
is invoked, and the host's pre-step boundary resolves it. The remote answers with
no `value` when the line parses to no command; since the codec folds an absent
value into `{}`, the discriminator is the presence of `commandId`.

Its argument shape is fixed:

```json
{"args":{"agentId","line","submittedAttachments":[
  {"type":"image","mediaType":"image/png","data":"<base64>","name":"a.png"},
  {"type":"file","receiptId":"<from an upload>"}]}}
```

`submittedAttachments` is required, empty or not — it was `images` through
0.1.2, and 0.1.3 renamed it when files joined, so the two releases refuse each
other's key. A non-empty batch is only accepted by a command whose
`commands/list` descriptor declares `input.attachments` (`input.images` through
0.1.2) — `/goal` and `/plan`, nothing else. The executor enforces that, not the
composer, but the client refuses first so the draft and the attachments survive
a refusal. Sub-command grammar stays with the host: `/plan off` and `/goal
pause` answer with an ordinary error result rather than being adjudicated here.

The `command` slot on `session/prompt`'s response, and the `unknown-command` /
`command-error` codes, are dead schema the host never populates.

### Files (no envelope on the way up)

Since 0.1.3 any file can accompany a prompt or a command. The bytes never ride
either: they are **staged first**, and the message cites a receipt.

```
POST /api/session/uploadFileBinary?sessionId=<id>&name=<display name>
Content-Type: application/octet-stream
<bytes>
```

The route is a fetch route rather than a Remote, so it answers HTTP 200 with a
**bare** result — `{"ok":true,"value":{"receiptId","file":{attachmentId,name,bytes}}}`
or `{"ok":false,"error":{code,message,details}}` — and not a `server-response`
envelope. Carrier misuse is a plain status: 415 for any other content type, 400
without `sessionId`, 405 for any method but POST. A chunked body is accepted, so
a provider that will not say a file's size costs only the progress display.

The same receipt is minted by the `fileUploads/upload` Remote from canonical
base64 (`{"args":{"agentId","request":{"data","name?"}}}`), which is what the
client falls back to — for files up to 20 MB — when the route answers 404: a
deployment that composes no file-upload service, or a relay that does not
proxy the path.

A receipt is scoped to the session that uploaded it and spent by the one prompt
or command that cites it; the host retires it when it observes the accepted
message. Citing a receipt from another session, or one already spent, is refused
as `session/attachment-invalid` with reason `FILE_NOT_STAGED`. A subagent
follow-up refuses files outright (`SUBAGENT_FILE_UNSUPPORTED`).

The stored file is verbatim — no normalization, unlike images — and appears in
the log as a content block:

```json
{"type":"file","attachment":{"attachmentId":"sha256:…","name":"notes.txt","bytes":42}}
```

There is no read-back route for a file; the bytes are for the agent's own file
tools, and the client renders the block as a name-and-size chip.

### Downloads (no envelope)

`GET /api/session.export?sessionId=<id>[&includeDescendants=true]` streams the
session-log ZIP as an attachment (`Content-Disposition: attachment;
filename="dsh-session-<id>.zip"`). It is answered directly, not through an RPC.

### Server → client

Unary response (HTTP 200):

```json
{"type":"server-response","rpcId":"<same>","result":{"ok":true,"value":{}}}
{"type":"server-response","rpcId":"<same>","result":{"ok":false,"error":{"code":"session/agent-busy","message":"...","details":{}}}}
```

### Answering a question request

Pending requests arrive as **waterfall** frames on `$events` (below) and are
settled through the Gateway's own unary endpoint, `POST /api/$events/result`:

```json
{"type":"client-request","rpcId":"<uuid>","method":"$events/result","payload":{
  "clientId":"<from the ready frame>","eventId":"<from the waterfall frame>",
  "outcome":{"kind":"result","value":{"answers":[…]}}}}
```

`clientId` binds the reply to the current connection generation and `eventId` to
one pending request; the host refuses a reply carrying a retired generation,
which is what stops an answer typed before a reconnect from resolving a request
the host has already replayed. There is no separate approval id any more — the
`eventId` is the correlation.

`outcome.kind` is one of `next` (decline, and let the host's own later listeners
try), `result` (claim it), or `rejected` (fail it). The three are different
decisions, not degrees of one.

The value is the event's own return type: an approval answers a bare outcome
string (`allowed-once` | `rejected`), a question answers the answer object.
`custom` rides the **answer item**, not the batch:

```json
{"answers":[
  {"id":"approach","selected":["Rewrite (Recommended)"]},
  {"id":"notes","selected":[],"custom":"ship it on Friday"}]}
```

The payload is parsed with a schema that **strips** undeclared keys rather than
rejecting them, so a misplaced field does not fail — it vanishes, and the answer
is accepted without it. Then every clause below must hold, or the whole batch
comes back `bad-response` with the wait still open and the tool call still
blocked:

- one answer per question, **in request order**; the host pairs them by position
  and compares each `id`, so a partial or reordered batch is refused;
- no duplicate labels, and every label must be one the question itself offered
  (a question with no options can carry no selection);
- `custom` omitted when there is none — a present-but-blank one is a refusal;
- on a **single-select** question, `custom` and a non-empty `selected` are
  mutually exclusive, and `selected` may carry at most one label;
- a skipped question is still answered, with `selected: []` and no `custom`.

Dismissing the request is a rejection, not an answer:

```json
{"outcome":{"kind":"rejected","error":{
  "name":"UserQuestionError","code":"cancelled",
  "message":"the user closed this question request"}}}
```

Answering every item with an empty selection is *not* a dismissal: it is a valid
answer, and the model reads it as no preference. Nor is `next` a dismissal — it
delegates to the host's own remaining listeners rather than ending the request.

A failed `$events/result` fails the whole connection generation upstream, and the
host replays the pending event on the next one. The client therefore keeps **no**
retry queue for these: retrying would answer the same question twice.

### Streams (one WebSocket, bidirectional)

`/api/remote.mux` carries every server-initiated stream as an independently
cancellable **logical stream**. It replaces `/api/events.mux` and
`/api/events.host`, and unlike them it is written to — a client that sends
nothing receives nothing.

Client → host:

```json
{"type":"open","streamId":"1","endpoint":"session/follow","payload":{"args":{…}}}
{"type":"cancel","streamId":"1"}
```

Host → client:

```json
{"type":"item","streamId":"1","value":{…}}
{"type":"error","streamId":"1","error":{"code":"…","message":"…","details":{}}}
{"type":"end","streamId":"1"}
```

The host sends RFC 6455 Ping every `websocketHeartbeatIntervalMs` (30s default)
and the platform answers Pong at the protocol layer, so idle liveness needs no
application frame. Since 0.1.3 the host terminates a socket that misses two
pongs in a row; OkHttp answers every ping, so a healthy link never sees that.

The streams this app opens:

| Endpoint | Opens with | Carries |
|---|---|---|
| `$events` | `ready` | host notifications and pending waterfalls |
| `session/control` | complete `baseline` | queue, jobs and projections for every live session |
| `session/follow` | complete `snapshot` (+ `assistantStream` baseline) | one session's journal, and the reply being written |
| `workspace/follow` | complete `baseline` | the workspace registry |

**`$events`** is the connection's liveness source, opened unconditionally rather
than on demand. Its four frame kinds:

```json
{"type":"ready","clientId":"…","host":{"home":"/home/you"}}
{"type":"emit","event":"api-session/status","args":["<sessionId>",false]}
{"type":"waterfall","event":"approval/request","eventId":"…","agentId":"…","request":{…}}
{"type":"cancel","eventId":"…"}
```

`ready` must be first — every later answer is bound to the `clientId` it carries,
so a generation that never saw one has nothing to reply with. `emit` arguments
are **positional** (the host forwards the listener's own argument list), and an
`emit` is **never replayed** after a disconnect: state whose correctness depends
on recovery must come from a query or a stream baseline instead. A `cancel`
withdraws a delivered waterfall — another client answered first, or the host's
caller gave up.

**`session/follow`** takes `{address, maxMessages?, assistantStream?}` and opens
with one complete snapshot, then yields durable events and — when the follower
opted in — the live assistant frames:

```json
{"type":"snapshot","header":{"version":2,"id":"…","createdAt":…,"isSeeded":false},
 "cursor":42,"records":[…],"hasMore":true,"projections":{…},
 "assistantStream":{"revision":7,"activeAttempt":{…}}}
{"type":"event","event":{"type":"turn/end","seq":43,"time":…,"data":{…}}}
{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"…","revision":8,"index":0,"time":…,"chunk":{…}}}
```

Every reconnect sends another **complete** snapshot; there is no `afterSeq` and
no way to resume mid-stream. Following does not resume a stopped agent — the host
publishes a cold session's prepared snapshot immediately and promotes it in the
background — so opening a transcript is an observation, not an execution.

**`session/page`** is the unary half, and it **requires** `throughSeq`, which
comes from the matching follow generation's opening `cursor`. That pins the page
to the same log cut the live tail started from, which is what lets the two be
joined without a gap; `-1` denotes an empty log, and `beforeSeq` selects an older
page before that cut rather than replacing the cursor. There is no way to read
history without following first.

### History records

`records` (in a page or a follow snapshot) are plain events:

```json
{"type":"event","event":{"type":"turn/end","seq":4,"time":5,"data":{…},"ignorable":true}}
```

Through 0.1.2 a record could also be a packed `chunks` run of assistant deltas.
Session format v2 has nothing to pack: the deltas are not durable events any
more (below), so the record class is gone and every record reads as an event.
An `ignorable: true` marks an informational event a reader that does not
recognise its `type` may skip; this client renders unknown events as passthrough
rows either way.

### Session format v2: attempts and settlements

0.1.3 removed `assistant/chunk` from the durable log. A model attempt settles as
**one** event:

- `assistant/message` — `{turn, step, message, stream, usage?, interrupted?}`
  when the attempt produced a surface message. `stream` is the exact compact
  raw stream (below); the assembled `message` is what a transcript reads.
- `assistant/attempt` — `{turn, step, stream}`, new and log-only, when it did
  not: a retried provider failure, a cancellation before any text, a stream
  error. It carries no message and the client shows nothing for it.

The compact stream is lossless: a run of consecutive same-block deltas becomes
one record carrying the fragments and the inter-chunk time gaps, and every other
chunk is kept verbatim.

```json
[{"type":"text-chunks","time0":1000,"index":0,"dt":[5,-2],"texts":["Hel","lo"," there"]},
 {"type":"reasoning-chunks","time0":…,"index":0,"dt":[…],"texts":[…]},
 {"type":"tool-call-chunks","time0":…,"index":1,"dt":[…],"id":"call-1","name":"bash","args":[…]},
 {"type":"chunk","time":…,"chunk":{"type":"finish","reason":{"kind":"stop"}}}]
```

Member `k` of a run lands at `time0` plus the first `k` gaps (a gap may be
negative — the host's wall clock can step backwards). `name` on a tool-call run
is present only when every member agreed. The client expands this
(`core/session/AssistantStream.kt`) in exactly one place: the reconnect
baseline.

### The assistant stream

A follower that sent `assistantStream: true` receives the reply as it is
written, as process-local frames that are never logged, never replayed and
never paged:

```json
{"type":"start","attemptId":"s1:3","revision":7,"startedAfterSeq":41,"turn":2,"step":1}
{"type":"chunk","attemptId":"s1:3","revision":8,"index":0,"time":…,"chunk":{"type":"text-delta","index":0,"text":"Hel"}}
{"type":"end","attemptId":"s1:3","revision":9,"index":1,"outcome":{"kind":"committed","eventType":"assistant/message","seq":42}}
```

`chunk` carries the very shape `assistant/chunk` used to. Frames are dense:
`revision` counts every frame the host emitted (a `start` at revision 1 opens a
fresh agent lifecycle), `index` is the chunk's position in its attempt, and the
`end` carries the count it closes. `end.outcome` is `{kind:"committed",
eventType, seq}` naming the durable settlement, or `{kind:"abandoned"}` when no
durable event will follow.

The opening snapshot's `assistantStream` baseline is `{revision, activeAttempt?}`
where `activeAttempt` is `{attemptId, startedAfterSeq, turn, step, nextIndex,
stream}` — the compact prefix an attempt caught mid-stream had accumulated at the
cut, with `nextIndex` the position the next live chunk will carry. The host
forwards only frames after the cut, so the baseline and the tail join without
overlap. A host that predates the feature omits the key.

What the client does (`core/session/AssistantLiveState.kt`): keeps the one open
attempt's chunks, folds them **after** the durable window as a single
provisional message marked `streaming`, and retires the attempt the moment its
settlement lands on the event path — an `assistant/message` appended to the
surface, or an `assistant/attempt`, for the same turn and step, logged after
`startedAfterSeq`. A hole in `index` or an `end` whose count disagrees drops the
preview and nothing else: the settlement arrives regardless. This is a
simplification of the web client's `ClientAssistantStream`, which stages the
settlement until the `end` frame so the two swap atomically and reopens the
stream on a fault.

## Session projections

Several facts the UI needs never arrive as an RPC result — they are pushed as
`projection` frames on `session/control` and repeated in the `session/follow`
snapshot. The two baselines are produced independently, so neither is
authoritative on its own: the client keeps whichever carries the higher
watermark.

| Key | Carries |
|---|---|
| `permissions` | `{options:[{value,name,description?}], currentValue}` — the preset picker, read-only; the write side is `/permission <value>` |
| `sessionStats` | `{turns, steps, llmMs, toolMs, ttftMs, ttftSteps, decodeMs, decodeTokens}` — `ttftMs` is a **sum** over `ttftSteps`, and throughput must be derived from `decodeTokens / decodeMs` |
| `tokenUsage` | `{uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens}` |
| `contextPressure` / `contextBreakdown` | context-window occupancy, and what fills it |
| `imageLimits` | `{maxImageBytes, maxImagesPerMessage, maxMessageImageBytes, maxImagePixels, maxImageDimension, mediaTypes}` — the host's own attachment bounds, all of them enforced before upload; `maxImageDimension` is a per-side cap added in harness 0.1.0-rc.8, and 0.1.1-rc.2 raised every shipped bound (20MB per image, 200MB per message, 64M pixels, 8192px per side). Files have no published bound; the host refuses on upload |
| `modelSelection` | `{lastUsed, next}` — this session's durable model choice. The catalog lives at `session/modelCatalog` and describes the *host generation*, so the per-session selection lives here. `next` wins when present |
| `goal`, `todos`, `plan`, `title`, `sessionListMetadata` | the docks and list metadata |
| `turnOutline`, `schedule`, `turnBoundary` | new in 0.1.2–0.1.3; carried but not yet read by this client |

An absent key means the harness composes no such service; clients hide the
control rather than showing a dead one.

## Image attachments are normalized on ingest

Since harness 0.1.1-rc.2 the host re-encodes stored images down to its own
working size after admitting them, so the durable reference
(`{attachmentId, mediaType, bytes, width, height, name?, originalDimensions?}`)
describes the **stored** image, not the upload: `mediaType` may differ from what
the client sent, `attachmentId` is the digest of the normalized bytes, and an
animated GIF flattens to one frame. When scaling occurred, `originalDimensions`
carries the upload's pixel size. Byte-identical passthrough happens only for a
clean single-frame image already inside the normalization bounds. The client
treats `attachmentId` as opaque and renders what `session/attachment` returns,
so nothing here needs a branch — but nothing may assume the ref echoes the
upload either. Files, by contrast, are stored verbatim.

## Handshake & liveness

Connect = the `/api/remote.mux` socket opens (3 s budget) **and** the `$events`
stream yields its `ready` frame (5 s). That frame is the readiness signal
`host.describe` used to be: it proves the host installed its incremental
listeners before answering, so no baseline read can race them.

On loss: exponential backoff (500 ms × 2, cap 10 s, jitter), then a fresh
generation — new socket, new `$events`, new complete baselines from
`session/control` and `workspace/follow`, and a new complete snapshot (with its
assistant baseline) for whatever session is open. Ending `$events`, cleanly or
not, ends the generation.

## Authentication

Every `/api` request, the mux upgrade, the file-upload route and the
session-log download require a signed browser-session cookie. `GET
/?token=<launch token>` exchanges the token the harness prints at startup for
that cookie; the token is rejected on `/api` paths and in an `Authorization`
header.

**401** = no browser session. **403** = the `Host`/`Origin` fence refused where
the request came from; the app sends no `Origin`. The two need opposite remedies
and the app reports them separately (see `docs/COMPATIBILITY.md`).

Behind a relay the phone sends no harness cookie: the relay holds the harness
session and injects it upstream.

## Relay

`dsh-relay` is a harness plugin, not a different protocol. It terminates TLS,
authenticates, and forwards to the same loopback harness — so the envelopes, the
method set, the mux and the 3-second handshake budget are unchanged. Three things
differ on the wire.

From harness 0.1.2 the relay also has to hold a **harness** browser session and
inject it upstream. It strips the client's own `Cookie` before forwarding (that
header belongs to the relay's session, not the harness's), so without one every
proxied request is answered 401. The phone never carries the harness cookie
across the network.

**1. Every request carries a credential.**

```
Authorization: Bearer <token>
```

On `POST /api/*`, on `GET /api/session.export`, on `POST
/api/session/uploadFileBinary`, and on the `/api/remote.mux` **upgrade**. The
upgrade is the one that bites: it has no later request to carry a credential, a
relay refuses it at the handshake, and the loop can only report that as a stream
that would not open. Whether a relay proxies the upload route at all is the
relay's business; a 404 there sends the app to the `fileUploads/upload` Remote.

**2. TLS is pinned, not merely verified.**

`https://` itself is nothing new — the app has spoken it since 0.7.0, for a
harness behind a reverse proxy, where an ordinary CA decides. A relay is the
other way round. When the pairing payload carries a `fingerprint`, the app pins
it: SHA-256 over
the leaf certificate's DER SubjectPublicKeyInfo, base64 — byte-for-byte what the
relay publishes. Pinning replaces CA validation rather than following it
(`core/wire/RelayTls.kt`), because the relay's default posture is a self-signed
certificate the platform store would reject before a pin was ever consulted.

The relay mints a **new key** whenever the addresses its certificate covers
change, so a harness machine that moves networks rotates the pin. The app
reports that as a changed certificate, never as a transport failure, and never
falls back to CA validation.

**3. Pairing, once.**

Two calls outside `/api`, neither of which is forwarded upstream:

| Call | Auth | Answer |
|---|---|---|
| `GET /relay/health` | none | `{"service":"dsh-relay","ok":true}` |
| `POST /relay/pair` | none | `{deviceId, token, expiresAt, fingerprint?}`, or 403 `{"error":"pairing-failed"}` |

The claim body is `{"code","name"}` and **must** be sent with
`Content-Type: application/json`; without it the relay answers with an HTML page
rather than a token. It must go to the payload's `url` — the plain-HTTP
compatibility listener serves no relay route but `/relay/health`.

Neither call follows redirects. Relay 0.1.1 registers `/relay` on the harness's
own web server and redirects it to the relay's listener, so `/relay/health` is
also how the app resolves *where* a relay is: a 3xx naming the same path is the
harness pointing at it. Reading that answer rather than chasing it is what keeps
the recorded origin right — the target carries a different scheme and port, and
a 302 rewrites a POST into a GET, which would deliver the claim as a page view.

The QR payload is a single UTF-8 JSON object. `v` and `kind` are always present;
`plainUrl` and `fingerprint` are not. The app refuses any `kind` other than
`dsh-relay-pair` and any `v` above 1 — the one place it is strict rather than
lenient, because acting on a payload hands over a credential.

```json
{"v":1,"kind":"dsh-relay-pair","url":"https://192.168.1.5:3443",
 "plainUrl":"http://192.168.1.5:3444","fingerprint":"<base64 SPKI>",
 "code":"48213977","expiresAt":1755500000000}
```

### Status codes behind a relay

A relay answers **403, never 401**, for every unauthorized case of its own —
missing, expired, revoked, an untrusted `Host`, a cross-site marker, or a
privileged method that credential may not reach.

A 401 reaching the app from behind a relay therefore did not come from the relay:
it is the *harness* refusing the relay's own upstream request for want of a
browser session, forwarded verbatim. The app treats it as a pairing problem for a
relay address, because from the phone's side there is nothing else it could act
on — but the fix is on the relay.

| Code | Meaning | What the app does |
|---|---|---|
| 401 | the harness has no browser session (forwarded from upstream) | Stops the loop; against a relay this is an operator-side fault, not a pairing one |
| 403 | no usable credential | Stops the loop and says "pair again". No backoff — there is nothing to wait for. |
| 404 | path not proxied, or the harness lacks the capability | Existing `capability-unavailable` handling |
| 429 | rate limited or locked out | Backs off for `Retry-After`, defaulting to 60s when the header is absent — older relays omitted it on the lockout paths |
| 502 | the harness behind the relay is not answering | Existing reconnect handling |

403 is ambiguous with the harness's own `Host` fence, and no header on a rejected
upgrade disambiguates it. The app decides from what it already knows: an address
it holds a device token for reads 403 as "pair again", any other address reads it
as the trust fence. A 401 from a direct (non-relay) address is unambiguous — the
harness accepted where the request came from and has no session for this client —
and routes to the launch-token exchange instead.

### Discovery

The relay advertises `_dsh._tcp` over mDNS with TXT records `v=1`,
`relay=dsh-relay`, `tls=self-signed|files|off`, `plain=<port>` and
`pin=<base64 SPKI>`. The service port is the primary listener's. Nothing depends
on it: relay mode browses first and falls back to knocking 3443 and 3444 across
the phone's own /24.
