#!/usr/bin/env node
// Keeps the shared UI's string resources in step with the Android app's.
//
//   node tools/shared-strings/sync.mjs            write sharedUi/src/commonMain/composeResources/values*/strings.xml
//   node tools/shared-strings/sync.mjs --check    fail if those files are not what --write would produce
//   node tools/shared-strings/sync.mjs --prune    also delete, from the Android resources, the strings that
//                                                 only the shared UI uses (see docs/IOS.md, "Strings")
//
// Which strings the shared UI needs is read from its sources: every `Res.string.<key>` under
// `sharedUi/src`. A string new to the shared UI takes its text from the Android resources, so the
// wording is the one the translators already wrote, in the same 11 locales, and there is nothing to
// translate twice. Once a string has moved (--prune took it out of the Android resources, or the
// shared file already has it) the shared file is its home and the tool keeps what it says there. A
// string the shared code no longer reads is dropped from the shared files.
//
// The only change made to a string is the one that is not wording: Android's resource compiler
// reads `\'` as an apostrophe, and Compose Multiplatform does not, so the backslash is dropped.
// Everything else (`%1$s`, `%2$d`, `&amp;`, …) is copied as it is; `SharedStringsTest` in `:app`
// holds the two copies identical afterwards and fails on a placeholder Compose Multiplatform
// would not fill in.

import { readFileSync, writeFileSync, readdirSync, existsSync, mkdirSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const androidRes = join(root, 'app', 'src', 'main', 'res');
const sharedRes = join(root, 'sharedUi', 'src', 'commonMain', 'composeResources');
const sharedSrc = join(root, 'sharedUi', 'src');
const appSrc = join(root, 'app', 'src');

const args = new Set(process.argv.slice(2));
const check = args.has('--check');
const prune = args.has('--prune');

function walk(dir, accept, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, accept, out);
    else if (accept(path)) out.push(path);
  }
  return out;
}

const read = (path) => readFileSync(path, 'utf8').replace(/\r\n/g, '\n');

// The keys the shared UI reads.
const keys = new Set();
for (const file of walk(sharedSrc, (p) => p.endsWith('.kt'))) {
  for (const m of read(file).matchAll(/\bRes\.string\.([A-Za-z0-9_]+)/g)) keys.add(m[1]);
}

const stringPattern = /^[ \t]*<string name="([^"]+)"[^>]*>([\s\S]*?)<\/string>[ \t]*\n?/gm;

/** Every <string> of one Android values directory, in file order: name -> raw text. */
function androidStrings(dir) {
  const result = new Map();
  for (const name of readdirSync(dir).filter((n) => /^strings.*\.xml$/.test(n)).sort()) {
    for (const m of read(join(dir, name)).matchAll(stringPattern)) result.set(m[1], m[2]);
  }
  return result;
}

const unescape = (text) => text.replace(/\\'/g, "'");

const locales = readdirSync(androidRes).filter((n) => n === 'values' || /^values-[a-z]{2}(-r[A-Z]{2})?$/.test(n)).sort();
let failed = false;

const pending = [];
for (const locale of locales) {
  const dir = join(androidRes, locale);
  if (!existsSync(join(dir, 'strings.xml'))) continue;
  const strings = androidStrings(dir);
  const out = join(sharedRes, locale, 'strings.xml');
  // What the shared file says now, for a string that has already left the Android resources.
  const shared = new Map();
  if (existsSync(out)) for (const m of read(out).matchAll(stringPattern)) shared.set(m[1], m[2]);
  const lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>'];
  for (const key of [...keys].sort()) {
    if (strings.has(key)) lines.push(`    <string name="${key}">${unescape(strings.get(key))}</string>`);
    else if (shared.has(key)) lines.push(`    <string name="${key}">${shared.get(key)}</string>`);
    else {
      console.error(`${locale}: neither the Android resources nor the shared ones have "${key}"`);
      failed = true;
    }
  }
  lines.push('</resources>', '');
  pending.push({ out, text: lines.join('\n') });
}

// Nothing is written unless every string was found: a partial file is worse than a stale one.
for (const { out, text } of pending) {
  if (check) {
    if (!existsSync(out) || read(out) !== text) {
      console.error(`${relative(root, out)} is out of date; run: node tools/shared-strings/sync.mjs`);
      failed = true;
    }
  } else if (!failed) {
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, text);
  }
}

if (prune && !check && !failed) {
  // A string is the shared UI's alone when nothing under app/ names it any more.
  const appFiles = walk(appSrc, (p) => /\.(kt|xml|java)$/.test(p) && !/[\\/]res[\\/]values[^\\/]*[\\/]strings[^\\/]*\.xml$/.test(p));
  const appText = appFiles.map(read).join('\n');
  const stillUsed = (key) => new RegExp(`R\\.string\\.${key}\\b|@string/${key}\\b`).test(appText);
  const removable = [...keys].filter((k) => !stillUsed(k));
  console.log(`pruning ${removable.length} strings that only the shared UI uses; ${keys.size - removable.length} stay in both`);
  for (const locale of locales) {
    const dir = join(androidRes, locale);
    for (const name of readdirSync(dir).filter((n) => /^strings.*\.xml$/.test(n))) {
      const path = join(dir, name);
      const original = readFileSync(path, 'utf8');
      const crlf = original.includes('\r\n');
      let text = original.replace(/\r\n/g, '\n');
      for (const key of removable) {
        text = text.replace(new RegExp(`^[ \\t]*<string name="${key}"[^>]*>[\\s\\S]*?</string>[ \\t]*\\n`, 'm'), '');
      }
      if (crlf) text = text.replace(/\n/g, '\r\n');
      if (text !== original) writeFileSync(path, text);
    }
  }
}

if (failed) process.exit(1);
console.log(check ? 'shared strings are in sync' : `wrote ${keys.size} strings in ${locales.length} locales`);
