# Drift monitoring

ClawDEA wraps the Claude Code CLI as a subprocess, so upstream changes (new flags, renamed `stream-json` fields, MCP config schema additions) can silently break the integration. Two complementary mechanisms catch this drift: a **weekly GitHub Actions digest** that diffs the CLI's `--help` output and selected docs against a moving snapshot tag, and a **PR-time fixture replay test** that re-parses a recorded NDJSON transcript through `CliEventParser` and fails if a new event type appears.

This is separate from the in-plugin [drift detection](drift-detection.md) layer, which concerns itself with wiki/workspace freshness rather than upstream CLI changes.

## Related

- [Drift detection](drift-detection.md) — in-plugin wiki/workspace freshness (different concept)
- [CLI bridge](cli-bridge.md) — `CliEventParser` is the consumer that the fixture replay test exercises

## Key entry points

### Weekly digest
- [.github/workflows/claude-code-drift.yml](../../../.github/workflows/claude-code-drift.yml) — scheduled workflow definition
- [scripts/drift/fetch-sources.sh](../../../scripts/drift/fetch-sources.sh) — fetches `claude --help`, sub-command help, npm version metadata, and selected docs URLs
- [scripts/drift/diff-and-file.mjs](../../../scripts/drift/diff-and-file.mjs) — diffs against the `drift-snapshot` git tag and files an issue when a watchlisted regex matches a changed line
- [scripts/drift/watchlist.yaml](../../../scripts/drift/watchlist.yaml) — patterns that trigger an auto-filed issue (one entry per drift-sensitive contract)

### PR-time fixture replay
- `CliFixtureReplayTest` in `src/test/kotlin/com/adobe/clawdea/cli/` — replays the recorded NDJSON fixture and goes red if any line parses to `CliEvent.Unknown`
- `src/test/resources/cli-fixtures/` — recorded NDJSON transcripts; refresh by re-running a chat session against the latest CLI

## Gotchas

- The weekly workflow compares against a **moving git tag** (`drift-snapshot`), not against `main`. After triaging an auto-filed issue you must reseed the snapshot (force-push the tag to the latest commit on `drift-snapshot-source`) or the next run will re-file the same issue.
- `watchlist.yaml` regexes are matched against the **diff lines**, not the full file. A new flag added in a renamed section won't trigger unless its regex matches the literal `+ ...` line.
- The fixture replay test only covers event **shapes**, not semantics. A field whose value range changes (e.g. a new `tool_use_id` format) will not fail the test — only a brand-new top-level event type will.
- See [`docs/drift-monitoring.md`](../../../docs/drift-monitoring.md) for the full operator guide: adding watchlist entries, triaging auto-filed issues, refreshing the fixture, reseeding the snapshot tag.
