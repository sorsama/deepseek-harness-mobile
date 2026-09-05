#!/usr/bin/env node
/**
 * DSH Mobile traffic capture.
 *
 * Talks to a running DeepSeek Harness (DSH_URL, default http://127.0.0.1:3080),
 * records a few unary calls plus the logical streams the app opens, and writes
 * everything as JSON / NDJSON into capture-output/ (gitignored):
 *
 *   session.list.json      {"request": <client-request>, "response": <server-response>}
 *   session.page.json      same wrapper (only when session/list returned sessions)
 *   model.catalog.json     same wrapper
 *   commands.list.json     same wrapper for the session's command catalog
 *   events.ndjson          one {"request": null, "response": <$events frame>} per line
 *   session.follow.ndjson  one line per follow frame, including its opening snapshot
 *   session.control.ndjson one line per control frame, including its baseline
 *   workspace.follow.ndjson  one line per workspace frame
 *
 * Harness 0.1.2 changed three things this tool has to deal with:
 *
 *   - `host.describe` is gone. The host facts ride the `$events` stream's opening
 *     `ready` frame instead, so the capture reads them from there.
 *   - There is one WebSocket, `/api/remote.mux`, carrying every stream as a
 *     logical stream the client must explicitly open. It is not downlink-only.
 *   - The whole `/api` surface requires a browser-session cookie. Give this tool
 *     the launch token the harness prints at startup (DSH_TOKEN) and it performs
 *     the exchange, or hand it a cookie you already have (DSH_COOKIE).
 *
 * Environment:
 *   DSH_URL      base URL of the harness (default http://127.0.0.1:3080)
 *   DSH_TOKEN    launch token from the harness's startup URL (?token=...)
 *   DSH_COOKIE   a `dsh-auth-...=...` cookie, if you already have one
 *   DSH_SECONDS  how long to listen on the streams (default 5)
 */

import { randomUUID } from 'node:crypto';
import { appendFile, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import WebSocket from 'ws';

const BASE_URL = (process.env.DSH_URL ?? 'http://127.0.0.1:3080').replace(/\/+$/, '');
const rawSeconds = Number(process.env.DSH_SECONDS ?? 5);
const SECONDS = Number.isFinite(rawSeconds) && rawSeconds > 0 ? rawSeconds : 5;
const OUT_DIR = path.resolve(process.cwd(), 'capture-output');

/** The browser session every `/api` request needs; filled in by `authenticate()`. */
let COOKIE = process.env.DSH_COOKIE ?? null;

/** Builds a client-request envelope. */
function clientRequest(method, payload) {
  return { type: 'client-request', rpcId: randomUUID(), method, payload };
}

/** Converts an http(s):// base URL to its ws(s):// equivalent. */
function wsBase(url) {
  return url.replace(/^http/, 'ws');
}

/** Headers for an authenticated request. */
function authHeaders(extra = {}) {
  return COOKIE === null ? extra : { ...extra, cookie: COOKIE };
}

/**
 * Exchange the launch token for a browser-session cookie.
 *
 * The token is only accepted on the index route — never on an `/api` path and
 * never in an Authorization header — so this is a plain `GET /?token=…` whose
 * `Set-Cookie` is the whole point. The harness prints the URL carrying it once
 * per process; it is a credential, so treat the startup line as sensitive.
 */
async function authenticate() {
  if (COOKIE !== null) {
    console.log('using DSH_COOKIE for the browser session');
    return;
  }
  const token = process.env.DSH_TOKEN;
  if (token === undefined || token === '') {
    console.warn(
      'no DSH_TOKEN or DSH_COOKIE set — every /api call will be answered 401.\n' +
        '  Copy the token from the URL the harness prints at startup.',
    );
    return;
  }
  const res = await fetch(`${BASE_URL}/?token=${encodeURIComponent(token)}`, { redirect: 'manual' });
  const setCookie = res.headers.get('set-cookie');
  if (setCookie === null) {
    throw new Error(
      `token exchange returned HTTP ${res.status} with no Set-Cookie; ` +
        'the token may belong to an earlier harness process (it rotates on every start)',
    );
  }
  COOKIE = setCookie.split(';')[0];
  console.log(`exchanged the launch token for a browser session (${COOKIE.split('=')[0]})`);
}

/** POSTs a unary call and returns the request/response pair plus the HTTP status. */
async function postJson(endpoint, args) {
  const request = clientRequest(endpoint, { args });
  let res;
  try {
    res = await fetch(`${BASE_URL}/api/${endpoint}`, {
      method: 'POST',
      headers: authHeaders({ 'content-type': 'application/json' }),
      body: JSON.stringify(request),
    });
  } catch (err) {
    throw new Error(`cannot reach ${BASE_URL}/api/${endpoint} (${err.message})`);
  }
  const text = await res.text();
  let response;
  try {
    response = JSON.parse(text);
  } catch {
    response = text; // non-JSON body, e.g. the trust fence's 403 or a bare 401
  }
  if (res.status === 401) {
    console.warn(`${endpoint} -> 401: no browser session. Set DSH_TOKEN or DSH_COOKIE.`);
  }
  return { request, response, status: res.status };
}

/** Writes one {"request":..., "response":...} wrapper to a JSON file. */
async function writeCaptured(fileName, request, response) {
  const filePath = path.join(OUT_DIR, fileName);
  await writeFile(filePath, JSON.stringify({ request, response }, null, 2) + '\n');
  console.log(`wrote ${filePath}`);
  return filePath;
}

/**
 * Open the mux and record every logical stream in `streams` for SECONDS.
 *
 * One socket carries them all, so this opens each by name and demultiplexes by
 * `streamId` — which is also the shape the app itself has to implement, and the
 * reason a capture cannot just listen passively any more.
 *
 * @param streams array of `{ endpoint, args, file }`.
 * @returns the first item seen on each stream, keyed by endpoint.
 */
function captureStreams(streams) {
  return new Promise((resolve) => {
    const url = `${wsBase(BASE_URL)}/api/remote.mux`;
    const byId = new Map();
    const counts = new Map();
    const firstItem = {};
    let failed = false;
    const socket = new WebSocket(url, { headers: authHeaders() });

    socket.on('open', () => {
      console.log(`mux: connected to ${url}`);
      streams.forEach((stream, index) => {
        const streamId = String(index + 1);
        byId.set(streamId, stream);
        counts.set(streamId, 0);
        socket.send(
          JSON.stringify({
            type: 'open',
            streamId,
            endpoint: stream.endpoint,
            payload: { args: stream.args ?? {} },
          }),
        );
      });
    });

    socket.on('message', (data) => {
      let frame;
      try {
        frame = JSON.parse(data.toString());
      } catch {
        console.warn(`mux: skipping non-JSON message: ${data.toString().slice(0, 80)}`);
        return;
      }
      const stream = byId.get(frame.streamId);
      if (stream === undefined) return;
      if (frame.type === 'error') {
        console.error(`${stream.endpoint}: stream error ${frame.error?.code}: ${frame.error?.message}`);
        return;
      }
      if (frame.type !== 'item') return;
      const seen = counts.get(frame.streamId) + 1;
      counts.set(frame.streamId, seen);
      if (seen === 1) firstItem[stream.endpoint] = frame.value;
      appendFile(
        path.join(OUT_DIR, stream.file),
        JSON.stringify({ request: null, response: frame.value }) + '\n',
      ).catch((err) => console.error(`${stream.endpoint}: failed to write frame: ${err.message}`));
    });

    socket.on('error', (err) => {
      failed = true;
      console.error(`mux: websocket error: ${err.message}`);
    });

    socket.on('close', () => {
      for (const [streamId, stream] of byId) {
        const seen = counts.get(streamId);
        console.log(
          seen > 0
            ? `${stream.endpoint}: ${seen} item(s) written to ${path.join(OUT_DIR, stream.file)}`
            : `${stream.endpoint}: no items (${failed ? 'connection failed' : 'window elapsed'})`,
        );
      }
      resolve(firstItem);
    });

    setTimeout(() => {
      if (socket.readyState === WebSocket.OPEN) {
        for (const streamId of byId.keys()) {
          socket.send(JSON.stringify({ type: 'cancel', streamId }));
        }
        socket.close(1000, 'capture window elapsed');
      }
    }, SECONDS * 1000);
  });
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });
  console.log(`DSH capture: target ${BASE_URL}, ${SECONDS}s stream window, output ${OUT_DIR}`);
  await authenticate();

  // 1. session/list. Its sole argument really is named `_request`: the host method
  //    ignores it, but the gateway matches parameter names exactly.
  const sessionList = await postJson('session/list', { _request: {} });
  console.log(`session/list -> HTTP ${sessionList.status} ${JSON.stringify(sessionList.response)}`);
  await writeCaptured('session.list.json', sessionList.request, sessionList.response);

  // 2. The host-generation model catalog. Replaces both session.models and llm.models.
  const catalog = await postJson('session/modelCatalog', {});
  console.log(`session/modelCatalog -> HTTP ${catalog.status}`);
  await writeCaptured('model.catalog.json', catalog.request, catalog.response);

  const okValue = sessionList.response?.result?.ok === true ? sessionList.response.result.value : null;
  const items = Array.isArray(okValue) ? okValue : okValue?.items;
  const first = Array.isArray(items) && items.length > 0 ? items[0] : null;
  const sessionId = first?.sessionId ?? first?.id ?? null;

  if (sessionId === null) {
    console.log('session/list: no sessions, skipping the per-session captures');
  } else {
    // 3. The command catalog. It is the only way to see `input.images` against a
    //    live host, and it is session-addressed, so its argument is `agentId`.
    const commands = await postJson('commands/list', { agentId: sessionId });
    console.log(`commands/list -> HTTP ${commands.status}`);
    await writeCaptured('commands.list.json', commands.request, commands.response);
  }

  // 4. The streams. `$events` first: its `ready` frame carries the host facts that
  //    `host.describe` used to answer, and nothing else can be read before it.
  const address = sessionId === null ? null : { kind: 'session', sessionId };
  const streams = [
    { endpoint: '$events', args: {}, file: 'events.ndjson' },
    { endpoint: 'session/control', args: {}, file: 'session.control.ndjson' },
    { endpoint: 'workspace/follow', args: {}, file: 'workspace.follow.ndjson' },
  ];
  if (address !== null) {
    streams.push({
      endpoint: 'session/follow',
      // `assistantStream: true` is what the app sends: since harness 0.1.3 the durable log
      // carries no deltas, and the live `assistant-stream` frames are the only way to see a
      // reply being written. The opening snapshot then carries an `assistantStream` baseline.
      args: { request: { address, maxMessages: 60, assistantStream: true } },
      file: 'session.follow.ndjson',
    });
  }
  const firstItems = await captureStreams(streams);

  const ready = firstItems['$events'];
  if (ready?.type === 'ready') {
    console.log(`host home: ${ready.host?.home} (clientId ${ready.clientId})`);
  } else if (ready !== undefined) {
    console.warn(`$events opened with "${ready?.type}" rather than "ready"`);
  }

  // 5. A history page, which needs the follow snapshot's cursor: `session/page`
  //    will not answer without one, because a page is pinned to the same log cut
  //    the live tail opened at.
  const snapshot = firstItems['session/follow'];
  if (address !== null && snapshot?.type === 'snapshot') {
    const page = await postJson('session/page', {
      request: { address, throughSeq: snapshot.cursor, maxMessages: 60 },
    });
    console.log(`session/page -> HTTP ${page.status} (throughSeq ${snapshot.cursor})`);
    await writeCaptured('session.page.json', page.request, page.response);
  } else if (address !== null) {
    console.log('session/follow produced no snapshot, skipping session/page');
  }

  console.log('capture complete. Files under ' + OUT_DIR);
}

main().catch((err) => {
  console.error(`capture failed: ${err?.message ?? err}`);
  console.error(
    `Is the DSH harness running at ${BASE_URL}? Start it with \`dsh web\`, or forward a device port with \`adb reverse tcp:3080 tcp:3080\`.`,
  );
  process.exitCode = 1;
});
