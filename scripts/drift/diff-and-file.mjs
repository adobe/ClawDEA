#!/usr/bin/env node
// Diffs a previous and current snapshot, applies the watchlist regexes, and
// either creates a GitHub issue or appends a comment on the rolling
// drift-noise issue.
//
// Usage:
//   node diff-and-file.mjs \
//     --previous-file <path-or-empty-json> \
//     --current-file <path> \
//     --watchlist <path> \
//     --owner <gh-owner> \
//     --repo <gh-repo> \
//     [--dry-run]
//
// Side-effects via the `gh` CLI (must be authenticated; in GitHub Actions,
// set GH_TOKEN to ${{ secrets.GITHUB_TOKEN }}).
//
// Bootstrap-safe: when the previous snapshot is empty {}, the script logs and
// exits 0 without filing anything. The caller is expected to seed the
// drift-snapshot tag before enabling the schedule.
//
// Use --dry-run for local development to print intended actions without
// touching the GitHub API.

import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import yaml from 'js-yaml';

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? null : process.argv[i + 1];
}

function flag(name) {
  return process.argv.includes(`--${name}`);
}

const previousPath = arg('previous-file');
const currentPath = arg('current-file');
const watchlistPath = arg('watchlist');
const owner = arg('owner');
const repo = arg('repo');
const dryRun = flag('dry-run');

if (!previousPath || !currentPath || !watchlistPath || !owner || !repo) {
  console.error(
    'Usage: diff-and-file.mjs --previous-file P --current-file C ' +
      '--watchlist W --owner O --repo R'
  );
  process.exit(2);
}

const previous = readJsonOrEmpty(previousPath);
const current = readJsonOrEmpty(currentPath);
const watchlist = yaml.load(fs.readFileSync(watchlistPath, 'utf8'));

if (Object.keys(previous).length === 0) {
  console.log('No previous snapshot — bootstrap mode, no issues will be filed.');
  process.exit(0);
}

const cliVersion = (current['cli-version'] || 'unknown').trim();

let driftNoiseIssueNumber = null;
let issuesFiled = 0;
let noiseAppended = 0;

for (const entry of watchlist) {
  const prev = (previous[entry.id] || '').trim();
  const curr = (current[entry.id] || '').trim();
  if (prev === curr) continue;

  const newLines = diffNewLines(prev, curr);
  if (newLines.length === 0) continue;

  const re = new RegExp(entry.match, 'm');
  const matched = newLines.filter((line) => re.test(line));
  if (matched.length === 0) continue;

  const summary = matched.slice(0, 20).join('\n');
  const truncated =
    matched.length > 20 ? `\n…(+${matched.length - 20} more)` : '';
  const body = [
    `**Surface:** \`${entry.id}\``,
    `**Detected against:** Claude Code v${cliVersion}`,
    `**Watchlist match pattern:** \`${entry.match}\``,
    '',
    '## New / changed lines',
    '```',
    summary + truncated,
    '```',
    '',
    'Filed automatically by the drift watcher (`.github/workflows/claude-code-drift.yml`). ' +
      'Triage: keep open and convert to a real implementation issue, or close as wontfix. ' +
      'See umbrella #91.',
  ].join('\n');

  if (entry.triage === 'issue') {
    const title = `[claude-code v${cliVersion}] ${entry.id}: drift detected`;
    if (dryRun) {
      console.log(`[dry-run] would create issue: ${title}`);
    } else {
      ghRun([
        'issue',
        'create',
        '--repo',
        `${owner}/${repo}`,
        '--title',
        title,
        '--label',
        'claude-code-drift',
        '--body',
        body,
      ]);
    }
    issuesFiled++;
  } else if (entry.triage === 'noise') {
    if (dryRun) {
      console.log(
        `[dry-run] would append drift-noise comment for ${entry.id}`
      );
    } else {
      const issueNum = getOrCreateDriftNoiseIssue();
      ghRun([
        'issue',
        'comment',
        String(issueNum),
        '--repo',
        `${owner}/${repo}`,
        '--body',
        `### ${entry.id}\n\n${body}`,
      ]);
    }
    noiseAppended++;
  }
}

console.log(
  `Filed ${issuesFiled} issue(s); appended ${noiseAppended} drift-noise comment(s).`
);

// --- helpers ---

function readJsonOrEmpty(path) {
  try {
    const raw = fs.readFileSync(path, 'utf8');
    if (!raw.trim()) return {};
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

function diffNewLines(prev, curr) {
  const prevLines = new Set(
    prev.split('\n').map((s) => s.trim()).filter(Boolean)
  );
  return curr
    .split('\n')
    .map((s) => s.trim())
    .filter((s) => s && !prevLines.has(s));
}

function getOrCreateDriftNoiseIssue() {
  if (driftNoiseIssueNumber) return driftNoiseIssueNumber;
  const found = ghJson([
    'issue',
    'list',
    '--repo',
    `${owner}/${repo}`,
    '--state',
    'open',
    '--label',
    'claude-code-drift',
    '--search',
    'in:title drift-noise',
    '--json',
    'number,title',
    '--limit',
    '5',
  ]);
  const match = found.find((i) =>
    i.title.toLowerCase().includes('drift-noise')
  );
  if (match) {
    driftNoiseIssueNumber = match.number;
    return driftNoiseIssueNumber;
  }
  // gh issue create with --json prints { number, url, ... } on success.
  const created = ghJsonCreate([
    'issue',
    'create',
    '--repo',
    `${owner}/${repo}`,
    '--title',
    '[drift-noise] rolling Claude Code drift signal',
    '--label',
    'claude-code-drift',
    '--body',
    'Rolling issue for low-priority Claude Code drift signals. The drift watcher ' +
      'appends a comment per detected change in noise-triaged surfaces. See umbrella #91.',
  ]);
  driftNoiseIssueNumber = created.number;
  return driftNoiseIssueNumber;
}

function ghRun(args) {
  return execFileSync('gh', args, {
    stdio: ['ignore', 'inherit', 'inherit'],
  });
}

function ghJson(args) {
  const out = execFileSync('gh', args, { encoding: 'utf8' });
  return JSON.parse(out);
}

function ghJsonCreate(args) {
  // `gh issue create` prints the URL by default. Extract the issue number from
  // the URL trailing component since --json isn't supported on `create`.
  const url = execFileSync('gh', args, { encoding: 'utf8' }).trim();
  const num = parseInt(url.split('/').pop(), 10);
  if (!Number.isFinite(num)) {
    throw new Error(`Could not parse issue number from gh output: ${url}`);
  }
  return { number: num, url };
}
