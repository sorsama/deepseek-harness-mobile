#!/usr/bin/env node
/**
 * DSH Mobile traffic capture.
 *
 * Talks to a running DeepSeek Harness (DSH_URL, default http://127.0.0.1:3080),
 * records a few unary calls plus the two downlink event streams, and writes
 * everything as JSON / NDJSON into capture-output/ (gitignored):
 *
 *   host.describe.json    {"request": <client-request>, "response": <server-response>}
 *   session.list.json     same wrapper
 *   session.history.json  same wrapper (only when session.list returned sessions)
 *   commands.list.json    same wrapper for the session's command catalog
 *   events.mux.ndjson     one {"request": null, "response": <server-request frame>} per line
 *   events.host.ndjson    same wrapper for the host channel
 *
 * Environment:
 *   DSH_URL      base URL of the harness (default http://127.0.0.1:3080)
 *   DSH_SECONDS  how long to listen on the event streams (default 5)
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

/** Builds a client-request envelope. */
function clientRequest(method, payload) {
  return { type: 'client-request', rpcId: randomUUID(), method, payload };
}

/** Converts an http(s):// base URL to its ws(s):// equivalent. */
function wsBase(url) {
  return url.replace(/^http/, 'ws');
}

/** POSTs a unary call and returns the request/response pair plus the HTTP status. */
async function postJson(method, payload) {
  const request = clientRequest(method, payload);
  let res;
  try {
    res = await fetch(`${BASE_URL}/api/${method}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(request),
    });
  } catch (err) {
    throw new Error(`cannot reach ${BASE_URL}/api/${method} (${err.message})`);
  }
  const text = await res.text();
  let response;
  try {
    response = JSON.parse(text);
  } catch {
    response = text; // non-JSON body, e.g. the trust fence's 403
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
 * Listens on one event stream for SECONDS, appending one NDJSON line per frame:
 * {"request": null, "response": <server-request frame>}. Resolves when the socket
 * closes (after the capture window or on error).
 */
function captureStream(name, fileName) {
  return new Promise((resolve) => {
    const url = `${wsBase(BASE_URL)}/api/${name}`;
    const filePath = path.join(OUT_DIR, fileName);
    let frames = 0;
    let failed = false;
    const socket = new WebSocket(url);
    socket.on('open', () => console.log(`${name}: connected to ${url}`));
    socket.on('message', (data) => {
      let frame;
      try {
        frame = JSON.parse(data.toString());
      } catch {
        console.warn(`${name}: skipping non-JSON frame: ${data.toString().slice(0, 80)}`);
        return;
      }
      frames += 1;
      appendFile(filePath, JSON.stringify({ request: null, response: frame }) + '\n').catch((err) => {
        console.error(`${name}: failed to write frame: ${err.message}`);
      });
    });
    socket.on('error', (err) => {
      failed = true;
      console.error(`${name}: websocket error: ${err.message}`);
    });
    socket.on('close', () => {
      if (frames > 0) {
        console.log(`${name}: ${frames} frame(s) written to ${filePath}`);
      } else {
        console.log(`${name}: no frames received (${failed ? 'connection failed' : 'window elapsed'})`);
      }
      resolve();
    });
    setTimeout(() => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.close(1000, 'capture window elapsed');
      }
    }, SECONDS * 1000);
  });
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });
  console.log(`DSH capture: target ${BASE_URL}, ${SECONDS}s event window, output ${OUT_DIR}`);

  // 1. host.describe
  const describe = await postJson('host.describe', {});
  console.log(`host.describe -> HTTP ${describe.status} ${JSON.stringify(describe.response)}`);
  await writeCaptured('host.describe.json', describe.request, describe.response);

  // 2. session.list (cursor omitted when undefined)
  const sessionList = await postJson('session.list', {});
  console.log(`session.list -> HTTP ${sessionList.status} ${JSON.stringify(sessionList.response)}`);
  await writeCaptured('session.list.json', sessionList.request, sessionList.response);

  // 3. session.history for the first session, when sessions exist
  const okValue = sessionList.response?.result?.ok === true ? sessionList.response.result.value : null;
  const items = Array.isArray(okValue) ? okValue : okValue?.items;
  if (Array.isArray(items) && items.length > 0) {
    const first = items[0];
    const sessionId = first?.sessionId ?? first?.id;
    if (sessionId === undefined || sessionId === null) {
      console.warn('session.list returned sessions but no sessionId/id field was found; skipping session.history');
    } else {
      const history = await postJson('session.history', { sessionId, maxMessages: 200 });
      console.log(`session.history -> HTTP ${history.status} ${JSON.stringify(history.response)}`);
      await writeCaptured('session.history.json', history.request, history.response);

      // 3b. The command catalog. It is the only way to see `input.images` against a live host,
      // and it is a typert Remote rather than an ordinary method, so the args object is nested.
      const commands = await postJson('commands/list', { args: { agentId: sessionId } });
      console.log(`commands/list -> HTTP ${commands.status} ${JSON.stringify(commands.response)}`);
      await writeCaptured('commands.list.json', commands.request, commands.response);
    }
  } else {
    console.log('session.list: no sessions, skipping session.history');
  }

  // 4. Event streams
  await Promise.all([
    captureStream('events.mux', 'events.mux.ndjson'),
    captureStream('events.host', 'events.host.ndjson'),
  ]);

  console.log('capture complete. Files under ' + OUT_DIR);
}

main().catch((err) => {
  console.error(`capture failed: ${err?.message ?? err}`);
  console.error(
    `Is the DSH harness running at ${BASE_URL}? Start it with \`dsh web\`, or forward a device port with \`adb reverse tcp:3080 tcp:3080\`.`,
  );
  process.exitCode = 1;
});
