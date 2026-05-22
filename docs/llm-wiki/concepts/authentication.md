# Authentication

**Purpose** Resolve which credentials provider (Anthropic API key, AWS Bedrock, Google Vertex, or Claude subscription OAuth) the CLI and gateway should use, and inject those credentials into the CLI subprocess environment.

## Invariants

- The **effective provider** is not always the configured provider. If the user-configured provider has no credentials but a different provider does (typically from environment variables), `AuthManager.effectiveProviderId()` returns that other provider so model catalog, preflight, and CLI env stay aligned with what the CLI will actually pick up ([AuthManager.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/AuthManager.kt)).
- `AuthManager.preflight()` runs against the **effective** provider, not the configured one. This means a user with `ANTHROPIC_API_KEY` exported but "Subscription" selected in settings will still pass preflight ([AuthManager.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/AuthManager.kt)).
- `AuthManager.applyToEnvironment(env)` only writes the active provider's variables. It must run **after** `System.getenv()` is copied into the CLI env map so explicit settings override inherited shell env, but before the process is spawned ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).
- Subscription auth shells out to `claude auth login --claudeai` as a streaming subprocess. ClawDEA watches stdout for the paste-code prompt but lets the **CLI itself** open the browser — never opens `https://...` from the plugin ([SubscriptionAuth.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/SubscriptionAuth.kt)).
- The `claude` binary path is resolved through `resolveClaudeCliPath()`. IntelliJ launched from Finder/Dock does **not** inherit the user's shell PATH, so the resolver also probes common install locations (`/usr/local/bin`, `/opt/homebrew/bin`, `nvm`, `volta`) ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).

## Resolution pipeline

1. **Configured provider** — `ClawDEASettings.state.apiProvider` is the user's choice in Settings → Tools → ClawDEA (`anthropic` | `bedrock` | `vertex` | `subscription`).
2. **Effective provider** — `AuthManager.effectiveProviderId()`:
   - if the configured provider's `isConfigured()` returns true → use it
   - else if any **other** provider's `isConfigured()` returns true → use that one
   - else → fall back to the configured id (so preflight produces the right "not configured" message)
3. **Active provider** — `AuthManager.activeProvider()` looks up the effective id in the `providers` map, defaulting to `anthropic` if missing.
4. **Apply to env** — `activeProvider().applyToEnvironment(env)` writes the credential vars (`ANTHROPIC_API_KEY`, `AWS_BEARER_TOKEN_BEDROCK` + region, `GOOGLE_APPLICATION_CREDENTIALS`, OAuth-cached subscription tokens).
5. **Preflight** — `AuthManager.preflight()` calls `activeProvider().validate()` returning `AuthValidation.Ok` or a structured failure (`MissingApiKey`, `BadCredentials`, `NetworkError`).
6. **CLI launch** — `CliProcess.preflightChecks(cliPath, env, authCheck)` blocks the spawn if preflight failed, surfacing the error to the chat panel via `CliStartException`.

## Anti-patterns

- **Using `state.apiProvider` directly to decide CLI flags or model catalog** — Always go through `AuthManager.effectiveProviderId()`. Otherwise, when the user has env-var credentials for a different provider, the catalog and preflight will desync from what the CLI actually picks up.
- **Opening the OAuth login URL from plugin code** — Causes a double browser window because the CLI opens it too. The `SubscriptionAuth` flow is "watch stdout for paste-code prompt, write code back to stdin"; the CLI is responsible for the browser launch.
- **Calling `Runtime.getRuntime().exec("claude")` directly** — Will fail when IntelliJ was launched from Finder/Dock. Always go through `resolveClaudeCliPath()`.
- **Persisting OAuth tokens to `ClawDEASettings`** — Tokens are owned by the CLI's own credential cache (`~/.claude/.credentials.json`). The plugin only probes status via `claude auth status`, never writes tokens.

## Source pointers

- [AuthManager.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/AuthManager.kt) — provider registry, effective-provider fallthrough, env application, preflight
- [AuthProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/AuthProvider.kt) — provider interface (`isConfigured`, `validate`, `applyToEnvironment`)
- [AnthropicAuthProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/AnthropicAuthProvider.kt) — `ANTHROPIC_API_KEY` provider
- [BedrockAuthProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/BedrockAuthProvider.kt) — AWS Bedrock bearer token + region
- [VertexAuthProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/VertexAuthProvider.kt) — Google Vertex service account
- [SubscriptionAuthProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/SubscriptionAuthProvider.kt) — OAuth subscription provider
- [SubscriptionAuth.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/SubscriptionAuth.kt) — interactive `claude auth login --claudeai` driver
- [SubscriptionAuthProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/SubscriptionAuthProbe.kt) — `claude auth status` probe
