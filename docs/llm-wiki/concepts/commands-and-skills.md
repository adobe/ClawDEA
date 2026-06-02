# Commands and skills

Slash commands and Claude Code skills are exposed through a single `CommandRegistry` keyed by lowercase name. Commands are registered at session start (built-ins, knowledge-layer handlers) or discovered at runtime (skills scanned from plugin caches and user directories). Resolution is exact-match: the registry has no aliases or wildcard logic — same handler under multiple names is just multiple registrations.

## Related

- [Chat UI](chat-ui.md) — `SlashCommandManager` parses input and routes to the registry
- [CLI bridge](cli-bridge.md) — `BridgeForwardHandler` and `BridgeExpandingHandler` send commands through the CLI subprocess
- [Knowledge layer](knowledge-layer.md) — `/note`, `/promote-to-wiki`, `/seed-wiki`, `/refresh-wiki` operate on this layer

## Key entry points

### Registry
- [CommandRegistry.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/CommandRegistry.kt) — register / unregister / resolve, deduplicates handlers shared across aliases
- [CommandInfo.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/CommandInfo.kt) — description and arg hint shown in `SlashCompletionProvider`
- [SlashCompletionProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/SlashCompletionProvider.kt) — popup completion in the chat input

### Handler types
- [LocalHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/handlers/LocalHandler.kt) — runs entirely in-plugin (e.g. `/clear`, `/skills`)
- [BridgeForwardHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/handlers/BridgeForwardHandler.kt) — forwards `/cmd args` directly to the CLI as-is
- [BridgeExpandingHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/handlers/BridgeExpandingHandler.kt) — expands an in-plugin prompt template, then forwards to the CLI (used by `/learn`, `/seed-wiki`, `/seed-workspace`, `/refresh-wiki`)
- [SkillHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/handlers/SkillHandler.kt) — invokes a discovered Claude Code skill by qualified name
- [IndexQueryCommandHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/handlers/IndexQueryCommandHandler.kt) — `/callers`, `/usages`, `/implementations`, `/supertypes` against IntelliJ indices
- [NoteAppendHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/handlers/NoteAppendHandler.kt) — `/note` writes to `.claude/notes/CURRENT.md`
- [PromoteToWikiHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/handlers/PromoteToWikiHandler.kt) — `/promote-to-wiki` moves entries from notes into wiki concept pages
- [WikiAuditCommandHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/handlers/WikiAuditCommandHandler.kt) — `/wiki-audit` runs `WikiAuditor` over the wiki directory
- [WikiGapHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/handlers/WikiGapHandler.kt) — `/wiki-gap` records a wiki suggestion

### Skills
- [SkillScanner.kt](../../../src/main/kotlin/com/adobe/clawdea/skills/SkillScanner.kt) — scans `SkillRoot.PluginCache` and `SkillRoot.Flat` roots for `SKILL.md` files
- [SkillFrontmatterParser.kt](../../../src/main/kotlin/com/adobe/clawdea/skills/SkillFrontmatterParser.kt) — parses YAML frontmatter from the `SKILL.md` file
- [SkillInfo.kt](../../../src/main/kotlin/com/adobe/clawdea/skills/SkillInfo.kt) — record holding name, description, qualified name, source root
- [SkillPickerDialog.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/SkillPickerDialog.kt) — UI for `/skills`

### Surfacing skills to Claude
Skill discovery (above) only populates the registry and the `/skills` picker. To make Claude itself aware of the available skills, `CliProcess` injects a static catalog into the CLI system prompt at session start:

- [CliProcess.buildSkillCatalogPrompt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt) — renders one `- <qualifiedName>: <description>` line per scanned skill, wrapped in a fixed header (`Available skills (invoke via slash command):`) and a fixed trailing instruction: *"When a skill matches the user's task, suggest invoking it with /<skill-name>."* Returns an empty string for an empty skill list.
- The catalog is appended in `CliProcess`'s `buildString { ... }` system-prompt assembly, gated by `settings.preloadSkillCatalog && skills.isNotEmpty()`.
- [ClawDEASettings.preloadSkillCatalog](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt) — defaults `true`; toggled by the "Preload skill catalog into system prompt" checkbox in [ClawDEASettingsPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettingsPanel.kt).

This is the integration seam for any task-based skill-recommendation work: today the catalog is static (every skill, every turn) and the suggestion instruction is a fixed string. Making recommendations task-aware (filtering or ranking skills against the user's prompt) would happen here.

## Gotchas

- The registry deduplicates **by handler reference** in `allCommands()`, not by name. Two different `LocalHandler` instances registered for the same logical command will both appear in the picker — register once, alias multiple names if needed.
- `BridgeExpandingHandler` runs the expansion **in-plugin** and then forwards the expanded text. The CLI never sees the original command name, so a CLI-side `/learn` slash command would be ignored.
- Skills are scanned from multiple roots and **deduplicated by qualified name** (`<plugin>:<skill>`). User-directory skills can shadow plugin-cache skills with the same qualified name; the scan order determines which wins.
- The system-prompt skill catalog (`buildSkillCatalogPrompt`) is built once when `CliProcess` starts and is **not** updated mid-session. Skills discovered or shadowed after the CLI launches won't appear to Claude until the process restarts, even though they show up in the `/skills` picker immediately.
- `/refresh-wiki` is a `BridgeExpandingHandler` — it does not call `DriftDetectionService.rescan()` directly. It expands a prompt instructing the wiki-author subagent to scan and edit, then routes through the CLI. The post-commit `CommitWikiDriftDetector` is what triggers the automatic in-plugin rescan.
