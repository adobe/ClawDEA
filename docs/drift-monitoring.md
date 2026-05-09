# Claude Code drift monitoring

ClawDEA wraps the Claude Code CLI as a subprocess. When Anthropic adds a new flag, renames a stream-json field, or changes the MCP config schema, ClawDEA needs to know — either to adopt the improvement or to keep working at all.

This document describes the monitoring infrastructure that keeps ClawDEA in sync with upstream changes. The system has two halves: a **lag detector** (weekly digest) and a **compat regression detector** (PR-time fixture replay).

---

## Why this exists

The MCP `alwaysLoad` field — which lets a server's tool schemas pre-load into the system prompt instead of being deferred behind tool search — was adopted only after a haphazard sighting in someone's terminal output. ClawDEA had been running without it for weeks. The audit triggered by that miss surfaced ~25 other Claude Code surfaces ClawDEA could adopt, harden against, or watch.

This monitoring system makes those discoveries automatic.

---

## Architecture

Three signals, three failure modes, all in this repository:

| Workstream | Cadence | Trigger | Output |
|---|---|---|---|
| **Drift digest** | Monday 09:00 UTC (cron) | watchlist regex matches a line that changed since the previous snapshot | auto-filed issue, or comment on rolling drift-noise issue |
| **Fixture replay** (PR-time) | every PR | `CliFixtureReplayTest` fails on the recorded `latest.ndjson` | red CI on the PR |
| **Live capture** (#119, deferred) | Saturday 09:00 UTC (cron) | new `@anthropic-ai/claude-code` published | auto-PR refreshing `latest.ndjson` |

The fixture-replay tripwire's failure mode is "a PR shows up red," fitting the existing review workflow with no new dashboards or notifications.

### Local Dream wiki maintenance

IDE-local ClawDEA can use Claude Code Dreams as an opportunistic semantic detector for `.claude/wiki/`. Dream-backed status feeds the same drift banner and `/refresh-wiki` flow as deterministic wiki drift. Automatic and default drift rescans perform only a cheap Dream due check/status update; actual semantic Dream invocation happens through explicit `/refresh-wiki --dream` and any future idle/background runner, not plain startup or default rescan.

Manual `/refresh-wiki --dream` bypasses elapsed, signal-count, and scan-throttle due gates, but still respects the Dream enable setting, filesystem lock, and active-turn safety.

The Dream path never edits source files. When **Auto-update wiki on drift** is enabled, only deterministic low-risk wiki cleanup can auto-apply; substantive concept-page creation and rewrites continue through diff review.

---

## File layout

```
.github/workflows/
  claude-code-drift.yml       Monday cron — runs the drift digest

scripts/drift/
  watchlist.yaml              source of truth: surfaces, sources, regexes, triage
  fetch-sources.sh            captures `claude --help`, npm version, docs URLs into JSON
  diff-and-file.mjs           Node script: diffs prev vs current, files issues / drift-noise comments
  package.json + lock         pins js-yaml@4.1.0 (only Node dep)

src/test/resources/cli-fixtures/
  latest.ndjson               recorded NDJSON turn against the latest CLI
  latest.version              `claude --version` of the recorder

src/test/kotlin/com/adobe/clawdea/cli/
  CliFixtureReplayTest.kt     PR-time tripwire: replays latest.ndjson through CliEventParser

docs/superpowers/specs/
  2026-04-29-claude-code-drift-monitoring-design.md   the original design spec
```

---

## The watchlist (`scripts/drift/watchlist.yaml`)

Each entry describes one Claude Code surface. Format:

```yaml
- id: cli-flags-passed
  source: { type: command, cmd: 'claude --help' }
  match: 'output-format|input-format|include-partial-messages|mcp-config|...'
  triage: issue
```

**Fields:**

- `id` — unique identifier; used as the JSON key in the snapshot and as the issue title prefix.
- `source` — where to capture text from:
  - `{ type: command, cmd: '<shell command>' }` — runs the command, captures combined stdout/stderr.
  - `{ type: url, url: '<https url>' }` — fetches via curl, runs through `strip_html` (drops `<script>`/`<style>` blocks, normalizes prose, filters bundle hashes / deploy IDs).
  - `{ type: npm, package: '<name>' }` — runs `npm view <name> version`.
- `match` — JavaScript-flavored regex (compiled with the `m` flag). When a line in the *new* snapshot matches and was *not* present in the previous snapshot, the entry fires.
- `triage` — `issue` files a new GitHub issue; `noise` appends a comment to the rolling drift-noise issue (#33).

**Adding a new entry.** Edit `watchlist.yaml`, push. The next scheduled run picks it up. There's nothing else to wire — `fetch-sources.sh` iterates entries dynamically.

**Tightening regexes.** If an entry produces too many false positives, narrow the match. If it misses legitimate drift, broaden it (and risk noise). The four URL-sourced entries (`stream-json-schema`, `mcp-config-schema`, `hooks-schema`, `settings-json-schema`) are the most prone to noise; the strengthened `strip_html` in `fetch-sources.sh` defangs the worst offenders (Next.js bundle hashes), but be conservative when adding more.

---

## The drift-snapshot tag

State for the digest lives in a single moving git tag, `drift-snapshot`, that points at an orphan commit containing one file: `.drift-snapshot.json`. Each weekly run:

1. Captures a fresh snapshot via `fetch-sources.sh`.
2. Reads the previous snapshot from the `drift-snapshot` tag (or `{}` on first run — bootstrap-safe).
3. Diffs and files issues per the watchlist.
4. Force-pushes `drift-snapshot` to a new orphan commit holding the new snapshot.

Because the tag is force-moved, no history accumulates — you don't pay storage for snapshots over time.

### Inspecting the current snapshot

```bash
git fetch --tags --force origin
git show drift-snapshot:.drift-snapshot.json | jq 'keys'

# Show one entry's content:
git show drift-snapshot:.drift-snapshot.json | jq -r '."cli-version"'
git show drift-snapshot:.drift-snapshot.json | jq -r '."cli-flags-passed"' | head -20
```

### Reseeding the tag

You'd reseed only if (a) the watchlist changes drastically and you want to silence false positives on the next run, or (b) the tag drifts in a way you want to ignore.

```bash
# 1. Capture a snapshot locally.
scripts/drift/fetch-sources.sh > /tmp/drift-snapshot.json

# 2. Build an orphan commit holding only the snapshot, point drift-snapshot at it.
git stash --include-untracked --keep-index 2>/dev/null
git checkout --orphan _drift_snapshot_seed
git rm -rf --cached . >/dev/null 2>&1 || true
find . -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +
cp /tmp/drift-snapshot.json .drift-snapshot.json
git add .drift-snapshot.json
git commit -m "drift snapshot reseed $(date -u +%Y-%m-%dT%H:%M:%SZ)" --quiet
git tag -f drift-snapshot
git push -f origin drift-snapshot

# 3. Return to main and clean up.
git checkout main
git branch -D _drift_snapshot_seed
git stash pop 2>/dev/null || true
```

The Saturday workflow (when #119 lands) and the Monday workflow both move the tag at the end of every successful run, so you rarely need to reseed manually.

---

## Triaging an auto-filed issue

When the Monday cron fires, you'll see new issues with titles like:

```
[claude-code v2.1.124] cli-flags-new: drift detected
```

Every such issue contains:
- The watchlist `id` that matched
- The CLI version detected
- The regex that fired
- Up to 20 changed/new lines from the relevant snapshot section

**Triage protocol:**

1. **Read the changed lines.** Is it real signal (new flag, renamed field, deprecated flag), or noise (CDN deploy ID that survived the cleaner, whitespace-only churn)?
2. **If noise:** close as `not planned` with a brief explanation, and tighten the regex or add a filter to `strip_html` (in `scripts/drift/fetch-sources.sh`) to prevent recurrence.
3. **If signal:** convert it into a real implementation issue with the right tier label (`tier-1-hardening` / `tier-2-ux` / `tier-3-surfaces`), or close as `wontfix` if ClawDEA explicitly chooses not to adopt.

The rolling drift-noise issue (#33) gets one comment per `triage: noise` watchlist hit. Scan it monthly. If a comment looks substantive, file a real follow-up.

---

## The fixture replay tripwire

Independent of the digest. Runs in `./gradlew test` on every PR.

**How it works.** `src/test/resources/cli-fixtures/latest.ndjson` is a recorded NDJSON turn from `claude -p`. `CliFixtureReplayTest` replays it line-by-line through `CliEventParser` and asserts every event is recognized — i.e. either parses to a concrete `CliEvent.*` subclass *or* matches the small `KNOWN_IGNORED_RAW_TYPES` allowlist for shapes ClawDEA intentionally doesn't model (`rate_limit_event`, `stream_event` sub-shapes other than text deltas).

**When it fires.** A new event type or field rename in upstream `claude` produces a new `rawType` not in the allowlist. The test fails with the offending line number and `rawType`, and the PR's CI goes red.

**Refreshing the fixture manually.**

```bash
mkdir -p src/test/resources/cli-fixtures
echo '{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Reply with the single word: hello"}]}}' \
  | claude -p --model sonnet \
      --output-format stream-json \
      --input-format stream-json \
      --verbose \
      --include-partial-messages \
  > src/test/resources/cli-fixtures/latest.ndjson
claude --version > src/test/resources/cli-fixtures/latest.version
./gradlew test --tests "com.adobe.clawdea.cli.CliFixtureReplayTest"
```

If the test fails, the failure annotates the unrecognized event. Decide:
- **Add parsing support** to `CliEventParser` for the new type (add a `CliEvent.*` subclass + a parse branch).
- **Add to allowlist** by appending the rawType to `KNOWN_IGNORED_RAW_TYPES` in the test, with a comment explaining why ClawDEA doesn't model it.

---

## The Saturday auto-refresh workflow (#119, deferred)

The plan is for a Saturday GitHub Action to:

1. Install the latest `@anthropic-ai/claude-code`.
2. Run a scripted `claude -p` invocation against an Anthropic API key (CI secret).
3. Open a PR replacing `src/test/resources/cli-fixtures/latest.ndjson`.

The PR's CI runs `CliFixtureReplayTest` against the new fixture. If the parser breaks, the auto-PR goes red — that's the tripwire firing. If green, you merge in 30 seconds.

**Why it's deferred.** Step 2 needs an Anthropic API key (or AWS credentials for Bedrock) stored as a GitHub Actions secret. Until that secret is provisioned, the workflow can't run end-to-end. Until then, the manual refresh procedure above is the fallback.

When the secret is available, the workflow is ~50 lines of YAML plus the `fixture-prompt.json` already drafted in `docs/superpowers/specs/2026-04-29-claude-code-drift-monitoring-design.md`.

---

## Operational runbook

### Run the drift watcher manually

```bash
gh workflow run claude-code-drift.yml --ref main
gh run watch -R adobe/ClawDEA \
  "$(gh run list -w claude-code-drift.yml -L 1 --json databaseId -q '.[0].databaseId')"
```

Expected on a quiet run: `Filed 0 issue(s); appended 0 drift-noise comment(s).`

### Test the diff filer locally without filing real issues

```bash
scripts/drift/fetch-sources.sh > /tmp/snap.json

# Bootstrap path (no previous):
node scripts/drift/diff-and-file.mjs \
  --previous-file <(echo '{}') \
  --current-file /tmp/snap.json \
  --watchlist scripts/drift/watchlist.yaml \
  --owner adobe --repo ClawDEA
# → No previous snapshot — bootstrap mode, no issues will be filed.

# Diff path with synthetic prev (use --dry-run to avoid posting):
jq '.["cli-flags-passed"] |= sub("--effort.*\\n";"")' /tmp/snap.json > /tmp/prev.json
node scripts/drift/diff-and-file.mjs \
  --previous-file /tmp/prev.json \
  --current-file /tmp/snap.json \
  --watchlist scripts/drift/watchlist.yaml \
  --owner adobe --repo ClawDEA \
  --dry-run
# → [dry-run] would create issue: ...
```

### Add a new source to the watchlist

1. Pick the source type: `command`, `url`, or `npm`.
2. Add an entry to `scripts/drift/watchlist.yaml`.
3. Verify locally: `scripts/drift/fetch-sources.sh > /tmp/snap.json && jq -r '."<your-id>"' /tmp/snap.json | head -20`.
4. Commit and PR.
5. The next Monday run picks it up. No bootstrap needed for new entries (their content will appear as "new" on first run, but only matched lines fire).

### Tighten a noisy entry

If an entry keeps filing false positives:

1. Inspect the noise: `git show drift-snapshot:.drift-snapshot.json | jq -r '."<id>"' > /tmp/old.txt && curl/run the source again > /tmp/new.txt && diff /tmp/old.txt /tmp/new.txt | grep ...`
2. Either narrow the regex in `watchlist.yaml`, or extend `strip_html` in `fetch-sources.sh` to filter the volatile pattern.
3. Close the false-positive issue as `not planned` with a comment pointing at the fix PR.

---

## Reference

- **Umbrella issue:** [#11](https://github.com/adobe/ClawDEA/issues/11)
- **Design spec:** `docs/superpowers/specs/2026-04-29-claude-code-drift-monitoring-design.md`
- **Audited CLI baseline:** v2.1.123 (published 2026-04-29)
- **Related issues:** #31 (drift digest), #32 (Saturday refresh, deferred)
