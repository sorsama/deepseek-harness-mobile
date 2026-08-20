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
| `DSH_SECONDS`| `5`                    | How many seconds to listen on the event streams |

## What it does

1. `POST /api/host.describe` — prints the result, saves `capture-output/host.describe.json`.
2. `POST /api/session.list` (empty payload; cursor omitted when undefined) — prints the
   result, saves `capture-output/session.list.json`.
3. If sessions exist, `POST /api/session.history` for the **first** session with
   `maxMessages: 200` — prints the result, saves `capture-output/session.history.json`.
   Then `POST /api/commands/list` for the same session, saved as
   `capture-output/commands.list.json`.
4. Opens `ws://…/api/events.mux` and `ws://…/api/events.host` and logs every pushed
   frame as one NDJSON line for `DSH_SECONDS` seconds.

If the harness is not running, each step reports a graceful error and the script exits
with a non-zero status.

## Output files

All files live under `capture-output/` (root of the repo; already in `.gitignore`).

| File                    | Contents                                                                                          |
| ----------------------- | ------------------------------------------------------------------------------------------------- |
| `host.describe.json`    | `{"request": <client-request envelope>, "response": <server-response envelope>}`                  |
| `session.list.json`     | Same wrapper for the session list call.                                                           |
| `session.history.json`  | Same wrapper for the first session's history (only written when sessions exist). Its `projections` tail block is where `imageLimits` shows up. |
| `commands.list.json`    | Same wrapper for the session's command catalog — the only live view of which commands declare `input.images`. |
| `events.mux.ndjson`     | One line per pushed mux frame: `{"request": null, "response": <server-request frame>}`            |
| `events.host.ndjson`    | One line per pushed host frame: `{"request": null, "response": <server-request frame>}`           |

Server-pushed WebSocket frames have no client request, so their `request` field is `null`
and the whole `server-request` frame (including its fresh `rpcId`) is stored as `response`.

## Protocol recap

- Unary calls: `POST /api/<method>` with body
  `{"type":"client-request","rpcId":"<uuid>","method":"<m>","payload":{...}}` →
  `{"type":"server-response","rpcId":"<same>","result":{"ok":true,"value":...}}` or
  `{"ok":false,"error":{"code","message","details"}}`.
- Answers: `POST /api/respond` with `{"type":"client-response","rpcId","result":{...}}` →
  `{"accepted":true}`.
- Downlinks: `/api/events.mux` and `/api/events.host` are downlink-only WebSockets;
  the client must not send frames.
- A trust fence returns HTTP 403 for any request whose `Host` header is not loopback
  or allowlisted (loopback is always allowed).
