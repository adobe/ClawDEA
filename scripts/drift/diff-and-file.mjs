#!/usr/bin/env node
// Diffs a previous and current snapshot, applies the watchlist regexes, and
// routes each detected change by its `triage`:
//
//   issue → find-or-update a single *evergreen* issue per surface (deduped by
//           a hidden `<!-- drift-surface: <id> -->` marker). New detections on
//           a surface that already has an open issue are appended as comments
//           instead of opening a fresh version-stamped duplicate.
//   noise → append a comment to the single rolling drift-noise issue.
//
// Relevance gating: a watchlist entry may carry an `affects` allowlist. When
// set, a matched change only escalates to an `issue` if at least one changed
// line mentions one of those tokens; otherwise it is downgraded to noise. This
// stops spurious tickets like a deprecation notice for a flag ClawDEA never
// passes.
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
import { fileURLToPath } from 'node:url';
import yaml from 'js-yaml';

const MAX_LINES = 20;

// --- pure helpers (exported for tests) -------------------------------------

export function readJsonOrEmpty(path) {
  try {
    const raw = fs.readFileSync(path, 'utf8');
    if (!raw.trim()) return {};
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

export function diffNewLines(prev, curr) {
  const prevLines = new Set(
    prev.split('\n').map((s) => s.trim()).filter(Boolean)
  );
  return curr
    .split('\n')
    .map((s) => s.trim())
    .filter((s) => s && !prevLines.has(s));
}

export function surfaceMarker(id) {
  return `<!-- drift-surface: ${id} -->`;
}

export function evergreenTitle(id) {
  return `[drift] ${id}: review needed`;
}

// Decide what to do with a surface given the lines that changed this run.
// Returns { triage: 'issue' | 'noise' | null, lines }.
//   - null triage  → nothing matched, skip.
//   - relevance gating downgrades 'issue' → 'noise' when an `affects` allowlist
//     is present but no changed line mentions an allowlisted token.
export function selectLines(entry, newLines) {
  const re = new RegExp(entry.match, 'm');
  const matched = newLines.filter((line) => re.test(line));
  if (matched.length === 0) return { triage: null, lines: [] };

  if (entry.triage === 'issue' && Array.isArray(entry.affects) && entry.affects.length) {
    const relevant = matched.filter((line) =>
      entry.affects.some((token) => line.includes(token))
    );
    if (relevant.length === 0) {
      // Matched the surface, but nothing ClawDEA actually uses changed.
      return { triage: 'noise', lines: matched };
    }
    return { triage: 'issue', lines: relevant };
  }

  return { triage: entry.triage, lines: matched };
}

// The "what changed" block, shared by created-issue bodies, update comments,
// and noise comments.
export function detectionSection(entry, cliVersion, lines) {
  const shown = lines.slice(0, MAX_LINES).join('\n');
  const truncated =
    lines.length > MAX_LINES ? `\n…(+${lines.length - MAX_LINES} more)` : '';
  const out = [
    `**Surface:** \`${entry.id}\``,
    `**Detected against:** Claude Code v${cliVersion}`,
    `**Watchlist match pattern:** \`${entry.match}\``,
  ];
  if (entry.clawdea) out.push(`**Relevant ClawDEA code:** ${entry.clawdea}`);
  out.push('', '## New / changed lines', '```', shown + truncated, '```');
  return out.join('\n');
}

// Body for a freshly-created evergreen issue.
export function createIssueBody(entry, cliVersion, lines) {
  return [
    surfaceMarker(entry.id),
    detectionSection(entry, cliVersion, lines),
    '',
    'Filed automatically by the drift watcher (`.github/workflows/claude-code-drift.yml`). ' +
      'This is the **evergreen tracker** for this surface — later detections are ' +
      'appended as comments rather than opening new issues. Triage: convert to a ' +
      'real implementation issue, or close as wontfix. See umbrella #11.',
  ].join('\n');
}

// Comment appended to an existing evergreen issue on a fresh detection.
export function updateComment(entry, cliVersion, lines, when = new Date()) {
  const stamp = when.toISOString().slice(0, 10);
  return [
    `## Update — Claude Code v${cliVersion} (${stamp})`,
    '',
    detectionSection(entry, cliVersion, lines),
  ].join('\n');
}

// Find an existing open issue for a surface: prefer the hidden marker, fall
// back to a legacy version-stamped title that contains `<id>:` so older issues
// migrate cleanly into the evergreen scheme.
export function findSurfaceIssue(id, openIssues) {
  const marker = surfaceMarker(id);
  const byMarker = openIssues.find((i) => (i.body || '').includes(marker));
  if (byMarker) return byMarker;
  return (
    openIssues.find((i) => (i.title || '').includes(`${id}:`)) || null
  );
}

// --- main ------------------------------------------------------------------

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? null : process.argv[i + 1];
}

function flag(name) {
  return process.argv.includes(`--${name}`);
}

function ghRun(args) {
  return execFileSync('gh', args, { stdio: ['ignore', 'inherit', 'inherit'] });
}

function ghJson(args) {
  return JSON.parse(execFileSync('gh', args, { encoding: 'utf8' }));
}

function ghCreate(args) {
  // `gh issue create` prints the URL; parse the trailing issue number.
  const url = execFileSync('gh', args, { encoding: 'utf8' }).trim();
  const num = parseInt(url.split('/').pop(), 10);
  if (!Number.isFinite(num)) {
    throw new Error(`Could not parse issue number from gh output: ${url}`);
  }
  return num;
}

function main() {
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

  const slug = `${owner}/${repo}`;
  const previous = readJsonOrEmpty(previousPath);
  const current = readJsonOrEmpty(currentPath);
  const watchlist = yaml.load(fs.readFileSync(watchlistPath, 'utf8'));

  if (Object.keys(previous).length === 0) {
    console.log('No previous snapshot — bootstrap mode, no issues will be filed.');
    process.exit(0);
  }

  const cliVersion = (current['cli-version'] || 'unknown').trim();

  // Cache the open claude-code-drift issues once (number/title/body) so surface
  // dedup is a local lookup rather than a query per entry.
  let openIssuesCache = null;
  const openIssues = () => {
    if (openIssuesCache) return openIssuesCache;
    openIssuesCache = dryRun
      ? []
      : ghJson([
          'issue', 'list', '--repo', slug,
          '--state', 'open', '--label', 'claude-code-drift',
          '--json', 'number,title,body', '--limit', '100',
        ]);
    return openIssuesCache;
  };

  let driftNoiseIssue = null;
  const getDriftNoiseIssue = () => {
    if (driftNoiseIssue) return driftNoiseIssue;
    const found = openIssues().find((i) =>
      (i.title || '').toLowerCase().includes('drift-noise')
    );
    if (found) {
      driftNoiseIssue = found.number;
      return driftNoiseIssue;
    }
    driftNoiseIssue = ghCreate([
      'issue', 'create', '--repo', slug,
      '--title', '[drift-noise] rolling Claude Code drift signal',
      '--label', 'claude-code-drift',
      '--body',
      'Rolling issue for low-priority Claude Code drift signals. The drift ' +
        'watcher appends a comment per detected change in noise-triaged ' +
        'surfaces. See umbrella #11.',
    ]);
    return driftNoiseIssue;
  };

  let issuesFiled = 0;
  let issuesUpdated = 0;
  let noiseAppended = 0;

  for (const entry of watchlist) {
    const prev = (previous[entry.id] || '').trim();
    const curr = (current[entry.id] || '').trim();
    if (prev === curr) continue;

    const newLines = diffNewLines(prev, curr);
    if (newLines.length === 0) continue;

    const { triage, lines } = selectLines(entry, newLines);
    if (!triage || lines.length === 0) continue;

    if (triage === 'issue') {
      const existing = findSurfaceIssue(entry.id, openIssues());
      if (existing) {
        const comment = updateComment(entry, cliVersion, lines);
        if (dryRun) {
          console.log(`[dry-run] would comment on #${existing.number} (${entry.id})`);
        } else {
          ghRun(['issue', 'comment', String(existing.number), '--repo', slug, '--body', comment]);
          if (!(existing.title || '').startsWith('[drift]')) {
            ghRun(['issue', 'edit', String(existing.number), '--repo', slug, '--title', evergreenTitle(entry.id)]);
          }
        }
        issuesUpdated++;
      } else {
        const body = createIssueBody(entry, cliVersion, lines);
        if (dryRun) {
          console.log(`[dry-run] would create evergreen issue: ${evergreenTitle(entry.id)}`);
        } else {
          const num = ghCreate([
            'issue', 'create', '--repo', slug,
            '--title', evergreenTitle(entry.id),
            '--label', 'claude-code-drift',
            '--body', body,
          ]);
          // Make the new issue visible to later same-run dedup lookups.
          openIssues().push({ number: num, title: evergreenTitle(entry.id), body });
        }
        issuesFiled++;
      }
    } else {
      const body = `### ${entry.id}\n\n${detectionSection(entry, cliVersion, lines)}`;
      if (dryRun) {
        console.log(`[dry-run] would append drift-noise comment for ${entry.id}`);
      } else {
        ghRun(['issue', 'comment', String(getDriftNoiseIssue()), '--repo', slug, '--body', body]);
      }
      noiseAppended++;
    }
  }

  console.log(
    `Created ${issuesFiled} issue(s); updated ${issuesUpdated}; ` +
      `appended ${noiseAppended} drift-noise comment(s).`
  );
}

if (process.argv[1] && fileURLToPath(import.meta.url) === fs.realpathSync(process.argv[1])) {
  main();
}
