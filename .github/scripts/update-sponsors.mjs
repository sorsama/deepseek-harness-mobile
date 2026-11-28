// Rewrites the sponsors block in README.md from GitHub GraphQL JSON on stdin.
// Usage: gh api graphql ... | node .github/scripts/update-sponsors.mjs [README.md]
import { readFileSync, writeFileSync } from 'node:fs';

const START = '<!-- sponsors:start -->';
const END = '<!-- sponsors:end -->';
const file = process.argv[2] ?? 'README.md';

const esc = (s) =>
  String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const input = JSON.parse(readFileSync(0, 'utf8'));
const nodes = input?.data?.user?.sponsorshipsAsMaintainer?.nodes;
if (!Array.isArray(nodes)) {
  console.error('Unexpected response shape: no sponsorshipsAsMaintainer.nodes');
  process.exit(1);
}

const sponsors = nodes
  .filter((n) => n && n.privacyLevel === 'PUBLIC' && n.sponsorEntity?.login)
  .sort((a, b) => String(a.createdAt).localeCompare(String(b.createdAt)));

const lines = sponsors.map((n) => {
  const e = n.sponsorEntity;
  const name = e.name || e.login;
  return `<a href="https://github.com/${esc(e.login)}"><img src="${esc(e.avatarUrl)}" width="56" height="56" alt="${esc(e.login)}" title="${esc(name)}"></a>`;
});
const body = lines.length ? lines : ['No sponsors yet. You could be the first.'];

const text = readFileSync(file, 'utf8');
const s = text.indexOf(START);
const e = text.indexOf(END);
if (s < 0 || e < s) {
  console.error('Sponsor markers not found in ' + file);
  process.exit(1);
}
const eol = text.slice(s + START.length).startsWith('\r\n') ? '\r\n' : '\n';
const next =
  text.slice(0, s + START.length) + eol + body.join(eol) + eol + text.slice(e);

if (next === text) {
  console.log('Sponsors list unchanged.');
} else {
  writeFileSync(file, next);
  console.log('Sponsors list changed.');
}
