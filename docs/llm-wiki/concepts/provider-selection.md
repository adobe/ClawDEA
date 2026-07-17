# Provider selection and settings UI

**Purpose** Explain how a single global provider choice (`apiProvider`) flows from the Settings UI through the auth layer into the running chat, and — critically — how a provider switch that flips the *backend* (Codex ⇄ Claude) is applied by rebuilding the chat tab rather than restarting the bridge in place. [Authentication](authentication.md) covers *which credentials* a provider resolves to; this page covers *how each surface picks a provider* and *what happens on switch*.

## Invariants

- **There is exactly one provider setting, and it is global (application-level).** `ClawDEASettings.state.apiProvider` (`anthropic` | `bedrock` | `vertex` | `subscription` | `openai` | `openai-subscription`) is a single value on the app-level `ClawDEASettings` service — not per-project, not per-tab. Every chat tab, the completions gateway, the catalog probe, and the wiki subagent all read the same configured provider and resolve it through `AuthManager.effectiveProviderId()` ([ClawDEASettings.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt), [AuthManager.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/AuthManager.kt)).
- **Every surface picks its provider via `effectiveProviderId()`, never `state.apiProvider` directly.** Chat bridge construction ([CliBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliBridge.kt)), the model dropdown ([ModelComboManager.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ModelComboManager.kt)), the agent label ([AgentLabel.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/AgentLabel.kt)), the completions gateway ([ClaudeGateway.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ClaudeGateway.kt)), and the catalog probe ([ModelSelectorProbeStarter.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ModelSelectorProbeStarter.kt)) all call `effectiveProviderId()`. This keeps them aligned with whatever credentials the CLI actually picks up when env-var credentials differ from the configured provider (see [Authentication](authentication.md)).
- **A `CliBridge`'s backend is fixed at construction.** `useCodexBackend = isCodexProvider(effectiveProviderId)` is computed once in the constructor; the bridge then instantiates either `CodexAppServerProcess` or `CliProcess` and holds it for its whole life. `bridge.restart()` re-spawns the *same* backend — it cannot cross from `claude` to `codex`. Switching backends requires a brand-new bridge, which only a new `ChatSession` provides ([CliBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliBridge.kt)).
- **A backend-flipping provider switch rebuilds the tab; a same-backend switch restarts the bridge.** `ChatPanel`'s `SettingsChangedListener` compares `CliBridge.isCodexProvider(effectiveProviderId())` against `bridge.usesCodexBackend`. If they differ (Codex ⇄ Claude) and no turn is streaming, it calls `rebuildSessionForBackendChange()`. If they match (e.g. anthropic → bedrock), it just calls `bridge.restart(skills)` in place ([ChatPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt)).
- **`rebuildSessionForBackendChange` replaces the tool-window content and carries the conversation across as context.** It creates a new `ChatSession` seeded with `autoResumeSessionId = bridge.sessionId`, adds the new content *before* removing the old (so the tab count never hits zero and triggers the auto-"new Chat" listener), preserves selection, and disposes the old panel. Auto-resume replays the prior conversation as cross-backend context — history is not lost across the swap. Must run on the EDT ([ChatPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt)).
- **A backend switch is deferred while a turn is streaming.** The listener only rebuilds when `!turnController.isStreaming`; mid-stream, the switch is dropped for that event (settings apply is a one-shot notification, not a queued intent).
- **Model catalogs and the selected model are keyed by provider.** `state.modelCatalogs[providerId]` holds a per-provider catalog and `state.selectedModels["$providerId|$workingDirectory"]` holds the per-provider, per-project pick. Switching providers in the settings panel swaps the visible catalog table (`transientCatalogs[provider]`) rather than mutating one shared list; the chat model dropdown re-reads the catalog for the new effective provider ([ClawDEASettingsPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettingsPanel.kt), [ClawDEASettings.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt)).

## Settings UI structure

`ClawDEASettingsConfigurable` is the IntelliJ `Configurable` (Settings → Tools → ClawDEA); it owns a `ClawDEASettingsPanel` and delegates `isModified` / `apply` / `reset` to it ([ClawDEASettingsConfigurable.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettingsConfigurable.kt)).

- **Provider combo + CardLayout.** `apiProviderCombo` holds the six provider display names; `PROVIDER_KEYS` maps the selected index to the stored key. A `CardLayout` (`providerCards`) shows exactly one provider-specific sub-panel — Anthropic API key, Bedrock region+token, Vertex region+project, OpenAI API key, and the two subscription cards (`SubscriptionCardPanel`, `OpenAiSubscriptionCardPanel`). The combo's action listener calls `showProviderCard()` and, when the provider changed, flushes the current model table into `transientCatalogs` and reloads the table for the new provider ([ClawDEASettingsPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettingsPanel.kt)).
- **Check Connection** builds a throwaway `AuthProvider` from the current form values (`buildProviderFromForm`) and runs `testConnection()` off the EDT — it validates the form, not the persisted state.
- **Apply flow.** `ClawDEASettingsConfigurable.apply()` snapshots the old provider, writes the panel back into `state`, saves the per-provider model catalogs, and then:
  1. if `state.apiProvider` changed → publishes `ModelCatalogListener.onCatalogUpdated()` and kicks `ModelSelectorProbeStarter.runProbe()` (re-fetch the new provider's catalog);
  2. **always** publishes `SettingsChangedListener.onSettingsChanged()`, which is what each `ChatPanel` listens on to restart or rebuild ([ClawDEASettingsConfigurable.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettingsConfigurable.kt)).

## Backend-change flow (end to end)

1. User picks a different provider in Settings and clicks Apply.
2. `ClawDEASettingsConfigurable.apply()` writes `state.apiProvider`, refreshes the catalog probe (if provider changed), and fires `SettingsChangedListener.onSettingsChanged()`.
3. Each open `ChatPanel`'s listener computes `newIsCodex = isCodexProvider(effectiveProviderId())` and compares to `bridge.usesCodexBackend`.
   - **Backend flip (Codex ⇄ Claude), not streaming** → `rebuildSessionForBackendChange()`: new `ChatSession` on the correct backend, old tab replaced, conversation replayed via auto-resume.
   - **Same backend, running, not streaming** → `bridge.restart(skills)` in place; posts "Session restarted to apply new settings."
   - **Streaming** → deferred (no action this event).
4. In parallel, `ModelComboManager` receives `onCatalogUpdated()` and `refresh()`es the dropdown to the new provider's catalog and remembered pick.

## Model dropdown behaviour

`ModelComboManager` (extracted from `ChatPanel`) renders the model combo and persists picks. Selecting a model calls `setSelectedModelId(basePath, id, providerId = effectiveProviderId())` and, if a bridge is live, restarts it in place (`restartBridge`) — a model change never flips the backend, so a plain restart is correct. It subscribes to `ModelCatalogListener` (catalog refreshed), `SubscriptionAuthEventListener` / `CodexSubscriptionAuthEventListener` (surface a re-authenticate hint), and `CostSnapshotListener` (re-label the "Default (<model>)" entry once a turn resolves a concrete model) ([ModelComboManager.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ModelComboManager.kt)). See [Cost tracking and session model](cost-tracking.md) for how the resolved default is captured.

## Anti-patterns

- **Reading `state.apiProvider` to decide backend, label, catalog, or model** — always go through `AuthManager.effectiveProviderId()`. The configured provider and the effective provider diverge when env-var credentials exist for a different provider; reading the raw setting desyncs the bridge, dropdown, and label from what the CLI actually runs.
- **Trying to restart a bridge across a backend flip** — `bridge.restart()` re-spawns the backend it was constructed with. To move Codex ⇄ Claude you must build a new `ChatSession`; that is exactly what `rebuildSessionForBackendChange` does. Do not add a "switch backend" path to `CliBridge`.
- **Removing the old tab before adding the replacement** — `rebuildSessionForBackendChange` adds the new content first on purpose. Removing first would drop the tab count to zero and trip the tool window's auto-"new Chat" listener, spawning a stray empty tab.
- **Treating the provider as per-tab or per-project** — it is a single global app-level setting. Per-tab state (like the selected model) is keyed by `providerId|workingDirectory`, but the provider itself is shared across all tabs and projects.
- **Mutating one shared model catalog across providers** — catalogs are per-provider (`modelCatalogs[providerId]`). The settings panel keeps `transientCatalogs` per provider and flushes/reloads on provider switch; collapsing them loses the other providers' models.

## Source pointers

- [ClawDEASettingsPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettingsPanel.kt) — provider combo, CardLayout of provider sub-panels, Check Connection, per-provider model table, load/apply/isModified
- [ClawDEASettingsConfigurable.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettingsConfigurable.kt) — `Configurable` wrapper; fires `ModelCatalogListener` + catalog probe on provider change and `SettingsChangedListener` always
- [ClawDEASettings.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt) — `state.apiProvider`, per-provider `modelCatalogs`, per-`providerId|workingDirectory` `selectedModels`, `getCliModelId`/`getSelectedModelId`
- [AuthManager.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/AuthManager.kt) — `effectiveProviderId()` the single resolution point every surface calls
- [CliBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliBridge.kt) — backend fixed at construction (`useCodexBackend`), `usesCodexBackend`, `isCodexProvider`, `agentLabel`
- [ChatPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt) — `SettingsChangedListener` (restart-vs-rebuild decision), `rebuildSessionForBackendChange`
- [ModelComboManager.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ModelComboManager.kt) — model dropdown, per-provider persistence, in-place restart on model switch, catalog/auth/cost subscriptions
- [AgentLabel.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/AgentLabel.kt) — "Codex"/"Claude" label from the effective provider
- [ModelSelectorProbeStarter.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ModelSelectorProbeStarter.kt) — re-probes the effective provider's catalog on provider change

## Related

- [Authentication](authentication.md) — credential resolution and effective-provider fallthrough
- [Codex backend](codex-backend.md) — the `codex app-server` backend that OpenAI providers select
- [CLI bridge](cli-bridge.md) — bridge lifecycle and process wiring
- [Cost tracking and session model](cost-tracking.md) — resolved-default model that annotates the dropdown
