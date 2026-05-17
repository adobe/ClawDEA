# ClawDEA vs. Augment

| Capability | ClawDEA | Augment | Edge |
|---|---|---|---|
| **Codebase retrieval — symbol-anchored** | PSI: `find_callers`, `find_usages`, `find_implementations`, `find_supertypes`, `find_related_types`. Refactor-safe under overrides/generics. First-N-by-iteration-order, no relevance ranking. Degrades during `DumbService`. | Embedding retrieval — ranks by relevance, not refactor-aware. | ClawDEA when you have an anchor; Augment when you don't |
| **Codebase retrieval — natural language / no anchor** | `search_wiki` (curated authored knowledge) + `search_text` (literal/regex). Wiki bridges synonym gaps when seeded. | Embeddings retrieve directly on intent. | Augment, narrower margin once `/seed-wiki` has run |
| **Polyglot cross-language refs** | `ReferencesSearch` doesn't cross Kotlin↔TS, Java↔Python, code↔SQL/YAML. Wiki can document bindings; `search_text` for literals. | Embeddings match strings/names across all file types uniformly. | Augment, clearly |
| **Config files / non-code targets** | PSI is weak/absent for YAML/JSON/.env/Dockerfile/SQL. `search_text` finds literal hits; seeded wiki may document key flows; drift detection doesn't currently watch config keys. | Embeddings index everything as content; matches keys to code that consumes them. | Augment, with seeding closing some of the gap |
| **Project orientation** | `get_primer` returns `CLAUDE.md` + auto-generated module map + current focus in one tool call. | Reads `CLAUDE.md`-style files ad hoc; no assembled primer. | ClawDEA |
| **Authored project knowledge** | `.claude/wiki/` (concepts/sources/index) read+searched via MCP. `/seed-wiki` bootstraps from project state. Commit-driven detector + `wiki-author` subagent writes new pages when source changes invalidate concept pages. | None equivalent. | ClawDEA |
| **Knowledge maintenance** | `DriftDetectionService` runs `CodeRenameDetector` + `ManifestStaleDetector`; high-confidence fixes auto-applied (toggle), low-confidence surfaced via `/refresh-wiki`. `WikiAuditor` flags orphans/broken links. | N/A — embeddings always reflect current code. | ClawDEA on structural drift; Augment on conceptual drift the detectors miss |
| **Cross-repo context** | `.clawdea-workspace.md` + `list_workspace_repos`/`read_sibling_wiki`/`read_sibling_repo_state`. `/seed-workspace` auto-discovers via `WorkspaceRootDetector` + `CandidateClusterer` and proposes a manifest. | Single workspace; no manifest concept. | ClawDEA |
| **Compiler / IDE truth** | `McpIdeTools`: live diagnostics, symbol resolution from IntelliJ. | Reasons about it from source; no compiler hookup. | ClawDEA |
| **Live debugger** | `DebugBridge` + 21 MCP tools: launch (Java/Java-test/JS/Node), attach (Java/Node), breakpoints with Claude/user/borrowed tracking + cleanup, step over/into/out + run-to-cursor gated by `SuspendGate`, frames/variables/expand/evaluate/set-value via `XDebugger`. | None. | ClawDEA, outright |
| **Edit review** | Two layers: `propose_edit`/`propose_write` open native IntelliJ diff; `EditReviewCoordinator` fallback intercepts CLI built-in Edit/Write with inline Accept/Reject + revert. | `str-replace-editor` writes deterministically; relies on git/IDE undo. | ClawDEA on review fidelity; Augment on simplicity |
| **Inline completions** | `ClawDEACompletionProvider` + `ClaudeGateway`: COMPLETION profile pulls supertypes/related-types/imports + index hints. | Embedding-driven, retrieves analogous code from elsewhere in the repo. | Augment for "code similar to what I'm typing"; ClawDEA for type-correctness signals |
| **Slash commands & skills** | `CommandRegistry` (Local/BridgeForward/Skill); `SkillScanner` picks up the full `~/.claude/skills/` ecosystem. | Built-in tool set; no shared skill ecosystem. | ClawDEA |
| **Enterprise integrations (Jira/Confluence/GitHub)** | Skill-mediated; user installs skills like `jira-read-ticket`. | Bundled as first-class MCP tools. | Augment |
| **Task management as a primitive** | Inherits Claude Code CLI's task handling. | `add_tasks`/`update_tasks`/`view_tasklist` baked into the agent loop. | Augment |
| **Authentication / model freshness** | Subscription auth via `SubscriptionAuth` (no API key needed); models track the `claude` binary, no plugin release required. | Augment account/billing; releases on Augment cadence. | ClawDEA |
| **Cold-start / reindex resilience** | PSI tools short-circuit during `DumbService.isDumb()` (cold open, post-`git pull`, branch switch). Wiki/primer/workspace tools unaffected. | Pre-computed index, no equivalent failure mode. | Augment |
| **Surface / IDE coverage** | IntelliJ Platform 2026.1 only. | VS Code, JetBrains, CLI, web. | Augment |
| **Truncation / ranking in result sets** | `findCallers` first 10, `findUsages` first 15, `findImplementations` first 10 — iteration order. | Ranked by relevance, larger windows. | Augment |
| **Refactor safety of returned references** | PSI-correct (overrides, generics, imports resolved). | Approximate; can miss or over-match. | ClawDEA |
| **Workspace bootstrap effort** | One `/seed-wiki` + `/seed-workspace` turn (with diff review) → durable, self-maintained knowledge layer. | Zero — embeddings index automatically. | Augment for first query; ClawDEA after one bootstrap |

## Summary axis

- **Documented or seedable IntelliJ project:** ClawDEA is the deeper system — primer + curated wiki + workspace + PSI precision + live debugger + native diff review.
- **Polyglot, config-heavy, or never-bootstrapped repo:** Augment wins on retrieval breadth and zero-setup recall.
- **Dynamic / debugging:** ClawDEA only — Augment isn't in the category.
- **Breadth (cross-IDE, bundled enterprise integrations, task primitives):** Augment.
