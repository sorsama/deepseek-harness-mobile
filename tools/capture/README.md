# dsh-mobile-capture

Captures DSH Mobile traffic against a **running** DeepSeek Harness and writes the raw
JSON exchanges into `capture-output/` (gitignored) for replay, inspection, or golden
tests.

## Prerequisites

- **Node.js 18+** (uses the global `fetch`; developed against Node 24).
- **A reachable DeepSeek Harness.** The harness serves the DSH protocol on plain HTTP at
  `http://127.0.0.1:3080` by default. Make sure it is actually running:
  - Desktop: start it with `dsh web` (or however your harness build is launched).
  - Android device: forward the harness port first:
    `adb reverse tcp:3080 tcp:3080`
- Install the single dependency: `npm install` (pulls `ws`).

## Usage

Run from the repository root so `capture-output/` lands where `.gitignore` expects it:

```sh
npm install --prefix tools/capture
node tools/capture/capture.mjs            # default: http://127.0.0.1:3080, 5 s
```

Or directly:

```sh
node tools/capture/capture.mjs
DSH_URL=http://192.168.1.20:3080 DSH_SECONDS=10 node tools/capture/capture.mjs
```

### Environment

| Variable     | Default                | Meaning                                    |
| ------------ | ---------------------- | ------------------------------------------ |
| `DSH_URL`    | `http://127.0.0.1:3080`| Base URL of the running harness            |
| `DSH_TOKEN`  | —                      | Launch token from the URL the harness prints at startup |
| `DSH_COOKIE` | —                      | A `dsh-auth-…=…` cookie, if you already have one |
| `DSH_SECONDS`| `5`                    | How many seconds to listen on the streams  |

**One of `DSH_TOKEN` / `DSH_COOKIE` is required.** Harness 0.1.2 authenticates the
whole `/api` surface, so without a browser session every call is answered 401. The
token is only accepted on the index route (`GET /?token=…`), which is the exchange
this tool performs; it rotates on every harness start, and the startup line
carrying it should be treated as a credential.

## What it does

1. Exchanges `DSH_TOKEN` for a browser-session cookie (skipped when `DSH_COOKIE`
   is set).
2. `POST /api/session/list` — saves `capture-output/session.list.json`. Its sole
   argument really is named `_request`; the gateway matches parameter names exactly.
3. `POST /api/session/modelCatalog` — saves `capture-output/model.catalog.json`.
4. If sessions exist, `POST /api/commands/list` for the **first** session, saved as
   `capture-output/commands.list.json`.
5. Opens `ws://…/api/remote.mux` and, over that one socket, opens `$events`,
   `session/control`, `workspace/follow` and (when a session exists)
   `session/follow` as logical streams — logging every item as one NDJSON line for
   `DSH_SECONDS` seconds, then cancelling them.
6. `POST /api/session/page` using the follow snapshot's `cursor` as `throughSeq`,
   saved as `capture-output/session.page.json`. The call will not answer without
   it, which is why this step depends on step 5.

If the harness is not running, each step reports a graceful error and the script exits
with a non-zero status.

## Output files

All files live under `capture-output/` (root of the repo; already in `.gitignore`).

| File                      | Contents                                                                                        |
| ------------------------- | ----------------------------------------------------------------------------------------------- |
| `session.list.json`       | `{"request": <client-request envelope>, "response": <server-response envelope>}`                |
| `model.catalog.json`      | Same wrapper for the host-generation model catalog.                                             |
| `session.page.json`       | Same wrapper for the first session's history page. Since harness 0.1.3 every record is a plain event; an `assistant/message` carries its compact `stream`, and a step that produced no message settles as `assistant/attempt`. |
| `commands.list.json`      | Same wrapper for the session's command catalog — the only live view of which commands declare `input.images`. |
| `events.ndjson`           | One line per `$events` item. The first is the `ready` frame carrying the host facts.            |
| `session.follow.ndjson`   | One line per follow item; the first is the complete opening snapshot, and its `projections` block is where `imageLimits` shows up. The stream is opened with `assistantStream: true`, so the snapshot also carries an `assistantStream` baseline and, while a turn runs, `assistant-stream` items (`start` / `chunk` / `end`) interleave with the durable events. Run the capture while the agent is answering to see them. |
| `session.control.ndjson`  | One line per control item; the first is the complete baseline.                                  |
| `workspace.follow.ndjson` | One line per workspace item; the first is the complete baseline.                                |

Stream items have no client request, so their `request` field is `null` and the item's
own value is stored as `response` — the mux envelope (`type`/`streamId`) is stripped,
since it says nothing about the domain shape being captured.

## Protocol recap

- Unary calls: `POST /api/<namespace>/<method>` with body
  `{"type":"client-request","rpcId":"<uuid>","method":"<ns>/<m>","payload":{"args":{…}}}` →
  `{"type":"server-response","rpcId":"<same>","result":{"ok":true,"value":...}}` or
  `{"ok":false,"error":{"code","message","details"}}`.
- Streams: one WebSocket at `/api/remote.mux`, written to as well as read. The client
  sends `{"type":"open","streamId","endpoint","payload":{"args":{…}}}` and
  `{"type":"cancel","streamId"}`; the host answers `item` / `error` / `end` frames
  tagged with the same `streamId`.
- Answers to pending approvals and questions: `POST /api/$events/result` with
  `{clientId, eventId, outcome}` — not a separate response envelope.
- **401** means no browser session; **403** means the `Host`/`Origin` fence refused
  where the request came from. They need opposite fixes.

See `docs/PROTOCOL.md` for the full picture.
