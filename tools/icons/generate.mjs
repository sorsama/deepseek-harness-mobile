#!/usr/bin/env node
// Writes sharedUi/src/commonMain/kotlin/com/labteto/dshmobile/ui/components/DsIcons.kt: the Material icons the shared
// screens draw, as vectors copied from the androidx sources.
//
//   node tools/icons/generate.mjs            download the sources jars from Google's Maven and write the file
//   node tools/icons/generate.mjs --check    fail if the file is not what the sources say
//   node tools/icons/generate.mjs --jars <dir>   read core.jar and extended.jar from <dir> instead of downloading
//
// Why a file of copied paths: the Android app carries `androidx.compose.material:material-icons-extended`, and
// Compose Multiplatform's own icon artifact has the same classes under the same names, so both on the Android
// classpath is a duplicate class build failure. The sources jars of the version the app resolves are the
// reference: each icon's path commands are copied as they are written there (same numbers, same order), so the
// shared screens draw the pixels the app always drew. `DsIconsMatchMaterialTest` in `:app` holds every entry
// equal to the real `Icons.*` value, node for node.
//
// To add an icon: add a line to ICONS below (the name the shared code uses, then the Material name) and run the
// script. Nothing else is to be edited by hand in DsIcons.kt.

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateRawSync } from 'node:zlib';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const out = join(root, 'sharedUi', 'src', 'commonMain', 'kotlin', 'com', 'labteto', 'dshmobile', 'ui', 'components', 'DsIcons.kt');

/** The version of `material-icons-core` / `-extended` the Android app resolves (`./gradlew :app:dependencies`). */
const VERSION = '1.7.6';

/** [name in DsIcons, Material name]. Keep the order: it is the order of the file. */
const ICONS = [
  ['ChevronRight', 'AutoMirrored.Filled.KeyboardArrowRight'],
  ['ExpandMore', 'Filled.ExpandMore'],
  ['Settings', 'Filled.Settings'],
  ['Warning', 'Filled.Warning'],
  ['ArrowBack', 'AutoMirrored.Filled.ArrowBack'],
  ['Check', 'Filled.Check'],
  ['Close', 'Filled.Close'],
  ['Delete', 'Outlined.Delete'],
  ['MoreVert', 'Outlined.MoreVert'],
  ['QrCodeScanner', 'Outlined.QrCodeScanner'],
  ['Shield', 'Outlined.Shield'],
  ['ContentPaste', 'Outlined.ContentPaste'],
  // The session list (iOS M4b).
  ['Add', 'Filled.Add'],
  ['AddOutlined', 'Outlined.Add'],
  ['ArrowUpward', 'Filled.ArrowUpward'],
  ['KeyboardArrowDown', 'Filled.KeyboardArrowDown'],
  ['Archive', 'Outlined.Archive'],
  ['CallSplit', 'AutoMirrored.Outlined.CallSplit'],
  ['ContentCopy', 'Outlined.ContentCopy'],
  ['Edit', 'Outlined.Edit'],
  ['PushPin', 'Outlined.PushPin'],
  ['Refresh', 'Outlined.Refresh'],
  ['Restore', 'Outlined.Restore'],
  ['SettingsOutlined', 'Outlined.Settings'],
  ['SwapVert', 'Outlined.SwapVert'],
  // The chat's Markdown and code blocks (iOS M5a).
  ['ContentCopyFilled', 'Filled.ContentCopy'],
  // The chat's tool cards and their full-screen output viewer (iOS M5b).
  ['KeyboardArrowUp', 'Filled.KeyboardArrowUp'],
  ['Search', 'Filled.Search'],
  // The chat transcript: message actions, hold menus, file chips, call outcomes, the jump button (iOS M5c).
  ['TextFields', 'Outlined.TextFields'],
  ['ThumbUp', 'Filled.ThumbUp'],
  ['ThumbDown', 'Filled.ThumbDown'],
  ['Description', 'Outlined.Description'],
  ['Undo', 'AutoMirrored.Outlined.Undo'],
  ['AlternateEmail', 'Outlined.AlternateEmail'],
  ['Code', 'Outlined.Code'],
  ['FolderOpen', 'Outlined.FolderOpen'],
  ['Stop', 'Filled.Stop'],
  ['ErrorOutline', 'Outlined.ErrorOutline'],
  ['ArrowDownward', 'Filled.ArrowDownward'],
  // The composer, its full-screen page and send menu, and the chat top bar (iOS M6a).
  ['AttachFile', 'Filled.AttachFile'],
  ['OpenInFull', 'Filled.OpenInFull'],
  ['Bolt', 'Outlined.Bolt'],
  ['Schedule', 'Outlined.Schedule'],
  ['Groups', 'Outlined.Groups'],
  ['Diversity3', 'Outlined.Diversity3'],
  // The queue dock's row menu (iOS M6b).
  ['MoreVertFilled', 'Filled.MoreVert'],
];

const args = process.argv.slice(2);
const check = args.includes('--check');
const jarsAt = args.includes('--jars') ? args[args.indexOf('--jars') + 1] : null;

/** The entries of a zip file: name -> bytes. Stored and deflated entries only, which is all a sources jar has. */
function unzip(buffer) {
  const entries = new Map();
  let end = buffer.length - 22;
  while (end >= 0 && buffer.readUInt32LE(end) !== 0x06054b50) end--;
  if (end < 0) throw new Error('not a zip file');
  const count = buffer.readUInt16LE(end + 10);
  let at = buffer.readUInt32LE(end + 16);
  for (let i = 0; i < count; i++) {
    if (buffer.readUInt32LE(at) !== 0x02014b50) throw new Error('bad central directory');
    const method = buffer.readUInt16LE(at + 10);
    const size = buffer.readUInt32LE(at + 20);
    const nameLength = buffer.readUInt16LE(at + 28);
    const extraLength = buffer.readUInt16LE(at + 30);
    const commentLength = buffer.readUInt16LE(at + 32);
    const local = buffer.readUInt32LE(at + 42);
    const name = buffer.toString('utf8', at + 46, at + 46 + nameLength);
    entries.set(name, () => {
      const start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
      const data = buffer.subarray(start, start + size);
      if (method === 0) return data;
      if (method === 8) return inflateRawSync(data);
      throw new Error(`${name}: compression method ${method}`);
    });
    at += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

async function jar(artifact, file) {
  if (jarsAt) return readFileSync(join(jarsAt, file));
  const url = `https://dl.google.com/android/maven2/androidx/compose/material/${artifact}/${VERSION}/${artifact}-${VERSION}-sources.jar`;
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
  return Buffer.from(await response.arrayBuffer());
}

const sources = [
  unzip(await jar('material-icons-core-android', 'core.jar')),
  unzip(await jar('material-icons-extended-android', 'extended.jar')),
];

/** The source file of a Material icon, e.g. `AutoMirrored.Outlined.CallSplit`. */
function sourceOf(material) {
  const parts = material.split('.');
  const icon = parts.pop();
  const path = `commonMain/androidx/compose/material/icons/${parts.map((p) => p.toLowerCase()).join('/')}/${icon}.kt`;
  for (const entries of sources) if (entries.has(path)) return entries.get(path)().toString('utf8').replace(/\r\n/g, '\n');
  throw new Error(`${material}: no ${path} in the sources jars`);
}

/** One icon: its declared name, whether it mirrors, and the path commands of each `materialPath` block. */
function parse(material) {
  const text = sourceOf(material);
  const head = text.match(/materialIcon\(\s*name\s*=\s*"([^"]+)"(\s*,\s*autoMirror\s*=\s*true)?\s*\)\s*\{/);
  if (!head) throw new Error(`${material}: no materialIcon(...) call`);
  if (head[1] !== material) throw new Error(`${material}: the source names itself ${head[1]}`);
  const paths = [];
  const pathPattern = /materialPath(\([^)]*\))?\s*\{([^}]*)\}/g;
  for (const m of text.matchAll(pathPattern)) {
    if (m[1] && m[1].trim() !== '()') throw new Error(`${material}: materialPath${m[1]} has arguments this script does not copy`);
    const commands = m[2].split('\n').map((l) => l.trim()).filter(Boolean);
    for (const c of commands) {
      if (!/^[a-zA-Z]+\(([-0-9.f, ]*)\)$/.test(c)) throw new Error(`${material}: unexpected line "${c}"`);
    }
    paths.push(commands);
  }
  if (paths.length === 0) throw new Error(`${material}: no materialPath`);
  return { name: head[1], autoMirror: Boolean(head[2]), paths };
}

const HEADER = `package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Written by tools/icons/generate.mjs from the androidx ${VERSION} sources. Do not edit by hand: add the icon to
// the script's list and run it.

/**
 * The Material icons the shared screens draw, written out as vectors.
 *
 * Why not \`Icons.Filled.Settings\` and the rest: the Android app already carries
 * \`androidx.compose.material:material-icons-extended\`, and Compose Multiplatform's own icon artifact
 * has the same classes under the same names, so having both on the Android classpath is a duplicate
 * class build failure. (It also would put the whole extended set, thousands of icons, into the iOS
 * framework for the sake of a few.) Each path below is copied from the androidx ${VERSION} source of the
 * icon named in its comment, which is the version the Android app resolves, so the drawn pixels are
 * the ones the app has always drawn. \`DsIconsMatchMaterialTest\` in \`:app\` compares every one with the
 * real icon.
 *
 * Add an icon here as the screen that needs it moves, through \`tools/icons/generate.mjs\`.
 */
object DsIcons {
`;

const FOOTER = `}

/**
 * A 24dp Material icon: one black path, which \`Icon(tint = …)\` repaints. The path takes every value androidx's
 * \`materialPath\` gives it (the stroke ones too, though there is no stroke), so the copy equals the original.
 */
private fun icon(name: String, autoMirror: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
    icon(name, autoMirror, listOf(block))

/** An icon drawn as several black paths, one \`materialPath\` block each, in the source's order. */
private fun icon(name: String, autoMirror: Boolean = false, paths: List<PathBuilder.() -> Unit>): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    )
    for (block in paths) {
        builder.path(
            fill = SolidColor(Color.Black),
            stroke = null,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathBuilder = block,
        )
    }
    return builder.build()
}
`;

const body = [];
for (const [kotlinName, material] of ICONS) {
  const icon = parse(material);
  const mirror = icon.autoMirror ? ', autoMirror = true' : '';
  body.push(`    /** \`Icons.${material}\`. */`);
  body.push(`    val ${kotlinName}: ImageVector by lazy {`);
  if (icon.paths.length === 1) {
    body.push(`        icon("${icon.name}"${mirror}) {`);
    for (const command of icon.paths[0]) body.push(`            ${command}`);
    body.push('        }');
  } else {
    // One lambda per `materialPath` block, so the vector has the same path nodes as the original.
    body.push(`        icon(`);
    body.push(`            "${icon.name}"${mirror},`);
    body.push(`            paths = listOf<PathBuilder.() -> Unit>(`);
    for (const path of icon.paths) {
      body.push('                {');
      for (const command of path) body.push(`                    ${command}`);
      body.push('                },');
    }
    body.push('            ),');
    body.push('        )');
  }
  body.push('    }');
  body.push('');
}
body.pop();
const text = HEADER + body.join('\n') + '\n' + FOOTER;
// Line endings are the checkout's (Git may give CRLF on Windows); only the text is compared.
const now = existsSync(out) ? readFileSync(out, 'utf8') : '';

if (check) {
  if (now.replace(/\r\n/g, '\n') !== text) {
    console.error('DsIcons.kt is not what tools/icons/generate.mjs writes; run the script.');
    process.exit(1);
  }
  console.log(`DsIcons.kt matches the androidx ${VERSION} sources (${ICONS.length} icons).`);
} else {
  writeFileSync(out, now.includes('\r\n') ? text.replace(/\n/g, '\r\n') : text);
  console.log(`wrote ${ICONS.length} icons to ${out}`);
}
