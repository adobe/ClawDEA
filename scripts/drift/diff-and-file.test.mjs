import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  diffNewLines,
  selectLines,
  surfaceMarker,
  evergreenTitle,
  detectionSection,
  createIssueBody,
  updateComment,
  findSurfaceIssue,
} from './diff-and-file.mjs';

test('diffNewLines returns only trimmed lines absent from previous', () => {
  const prev = 'a\n  b  \nc';
  const curr = 'a\nb\nd\n  e';
  assert.deepEqual(diffNewLines(prev, curr), ['d', 'e']);
});

test('diffNewLines on no change returns empty', () => {
  assert.deepEqual(diffNewLines('x\ny', 'y\nx'), []);
});

test('selectLines: no match → null triage', () => {
  const entry = { id: 's', match: 'zzz', triage: 'issue' };
  assert.deepEqual(selectLines(entry, ['foo', 'bar']), { triage: null, lines: [] });
});

test('selectLines: plain issue keeps all matched lines', () => {
  const entry = { id: 's', match: 'foo', triage: 'issue' };
  const r = selectLines(entry, ['foo one', 'bar', 'foo two']);
  assert.equal(r.triage, 'issue');
  assert.deepEqual(r.lines, ['foo one', 'foo two']);
});

test('selectLines: noise stays noise', () => {
  const entry = { id: 's', match: '.*', triage: 'noise' };
  const r = selectLines(entry, ['2.1.160']);
  assert.equal(r.triage, 'noise');
  assert.deepEqual(r.lines, ['2.1.160']);
});

test('selectLines: affects downgrades to noise when nothing relevant changed', () => {
  // Mirrors the real --mcp-debug case: matched [DEPRECATED] but the flag is
  // not one ClawDEA passes.
  const entry = {
    id: 'cli-flags-deprecation',
    match: '\\[DEPRECATED',
    triage: 'issue',
    affects: ['--output-format', '--print'],
  };
  const r = selectLines(entry, ['--mcp-debug  [DEPRECATED. Use --debug instead]']);
  assert.equal(r.triage, 'noise');
});

test('selectLines: affects keeps issue and narrows to relevant lines', () => {
  const entry = {
    id: 'cli-flags-deprecation',
    match: '\\[DEPRECATED',
    triage: 'issue',
    affects: ['--output-format', '--print'],
  };
  const lines = [
    '--mcp-debug  [DEPRECATED. Use --debug instead]',
    '--print  [DEPRECATED. Use --query instead]',
  ];
  const r = selectLines(entry, lines);
  assert.equal(r.triage, 'issue');
  assert.deepEqual(r.lines, ['--print  [DEPRECATED. Use --query instead]']);
});

test('surfaceMarker and evergreenTitle are stable per id', () => {
  assert.equal(surfaceMarker('hooks-schema'), '<!-- drift-surface: hooks-schema -->');
  assert.equal(evergreenTitle('hooks-schema'), '[drift] hooks-schema: review needed');
});

test('detectionSection includes clawdea reference when present', () => {
  const entry = { id: 's', match: 'm', clawdea: 'CliProcess.kt' };
  const out = detectionSection(entry, '2.1.159', ['line a']);
  assert.match(out, /Relevant ClawDEA code:.*CliProcess\.kt/);
  assert.match(out, /line a/);
});

test('detectionSection omits clawdea line when absent and truncates', () => {
  const entry = { id: 's', match: 'm' };
  const lines = Array.from({ length: 25 }, (_, i) => `l${i}`);
  const out = detectionSection(entry, '2.1.159', lines);
  assert.doesNotMatch(out, /Relevant ClawDEA code/);
  assert.match(out, /\(\+5 more\)/);
});

test('createIssueBody embeds the dedup marker', () => {
  const entry = { id: 'hooks-schema', match: 'm' };
  const body = createIssueBody(entry, '2.1.159', ['x']);
  assert.ok(body.includes(surfaceMarker('hooks-schema')));
  assert.match(body, /evergreen tracker/);
});

test('updateComment is version + date stamped', () => {
  const entry = { id: 's', match: 'm' };
  const c = updateComment(entry, '2.1.159', ['x'], new Date('2026-06-05T00:00:00Z'));
  assert.match(c, /Update — Claude Code v2\.1\.159 \(2026-06-05\)/);
});

test('findSurfaceIssue prefers the hidden marker', () => {
  const open = [
    { number: 5, title: 'unrelated', body: 'nope' },
    { number: 7, title: 'whatever', body: `pre ${surfaceMarker('hooks-schema')} post` },
  ];
  assert.equal(findSurfaceIssue('hooks-schema', open).number, 7);
});

test('findSurfaceIssue falls back to a legacy version-stamped title', () => {
  const open = [
    { number: 9, title: '[claude-code v2.1.159] hooks-schema: drift detected', body: 'old, no marker' },
  ];
  assert.equal(findSurfaceIssue('hooks-schema', open).number, 9);
});

test('findSurfaceIssue returns null when nothing matches', () => {
  assert.equal(findSurfaceIssue('hooks-schema', [{ number: 1, title: 'x', body: 'y' }]), null);
});
