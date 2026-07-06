# Mentions and completions

The chat input supports two distinct completion mechanisms. **Mentions** (`@`) attach files and symbols to the user's message; they use IntelliJ's filename and short-name caches and a popup picker. **Inline completions** (Tab) populate the editor itself with Claude-generated continuations via the `ClaudeGateway` direct-API path. Neither uses the CLI subprocess — both run in-plugin for low latency.

## Related

- [Gateway completions](gateway-completions.md) — direct-API vs CLI fallback used by inline completions
- [Chat UI](chat-ui.md) — `MentionAutocompleteManager` is hosted by `ChatPanel` via `InputHost`
- [MCP server](mcp-server.md) — the same index APIs used by mentions also back the MCP `find_*` tools

## Key entry points

### Chat `@` mentions
- [MentionAutocompleteManager.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/MentionAutocompleteManager.kt) — popup lifecycle, key handling on the input area
- [MentionCompletionProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/MentionCompletionProvider.kt) — initial items (open tabs + recently git-modified) and prefix search
- [MentionPickerDialog.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/MentionPickerDialog.kt) — `@` then Tab opens the full Files + Symbols picker
- [RefParser.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/RefParser.kt) — `{[ref:type|payload]}` chat-only ref syntax used in cards (not in markdown files)
- [IndexQueryHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/IndexQueryHandler.kt) — backs the `/callers`, `/usages`, `/implementations`, `/supertypes` slash commands

### Inline completions (Tab in editor)
- [ClawDEACompletionProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/completions/ClawDEACompletionProvider.kt) — IntelliJ `DebouncedInlineCompletionProvider` registration; `isEnabled` gate
- [TriggerCompletionAction.kt](../../../src/main/kotlin/com/adobe/clawdea/completions/TriggerCompletionAction.kt) — the explicit hotkey/action ("Trigger Inline Completion", default `Alt+\`) that fires a `DirectCall` event
- [CompletionPromptBuilder.kt](../../../src/main/kotlin/com/adobe/clawdea/completions/CompletionPromptBuilder.kt) — assembles editor context (file, surrounding lines, imports)
- [CompletionSanitizer.kt](../../../src/main/kotlin/com/adobe/clawdea/completions/CompletionSanitizer.kt) — strips fences, trims to one suggestion
- [ContextEngine.kt](../../../src/main/kotlin/com/adobe/clawdea/context/ContextEngine.kt) — gathers PSI / files / git / index context per `ContextProfile`

## Gotchas

- The popup uses IntelliJ's filename cache and short-name cache (`PsiShortNamesCache`), not filesystem `find`. Files outside the project content roots will not appear.
- `RefParser`'s `{[ref:...|...]}` syntax is **chat-only**. It must not be written into wiki markdown — concept pages use standard Markdown links.
- `MentionAutocompleteManager.checkForMention` early-returns when the placeholder is showing; the placeholder is not user text and triggering the popup on it would emit ghost completions.
- Inline completions hit the `ClaudeGateway` which prefers the direct Anthropic API when an API key is present, falling back to `claude -p`. Subscription users always go through the CLI fallback path.
- **Manual-only mode** (`ClawDEASettings.state.completionsManualOnly`, off by default; toggle in Settings → the "Only request completions on hotkey" checkbox) suppresses *automatic* triggers so tokens are spent only when the user explicitly asks. `ClawDEACompletionProvider.isEnabled` returns false for any event that is not a manual trigger, where `isManualTrigger(event)` is `event is InlineCompletionEvent.DirectCall` (our own `TriggerCompletionAction` hotkey) **or** `InlineCompletionEvent.ManualCall` (the platform's built-in "Call Inline Completion"). Everything else — document changes, caret moves, focus, lookup — is automatic and dropped. This is independent of `completionsEnabled` (which switches the provider off entirely) — see issue #146.
