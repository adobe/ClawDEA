# Authentication

**Purpose** Resolve which credentials provider the CLI and gateway should use — the four Claude providers (Anthropic API key, AWS Bedrock, Google Vertex, Claude subscription OAuth) plus the two OpenAI providers that drive the codex backend (`openai` API key, `openai-subscription` ChatGPT sign-in) — and inject those credentials into the CLI subprocess environment.

## Invariants

- The **effective provider** is not always the configured provider. If the user-configured provider has no credentials but a different provider does (typically from environment variables), `AuthManager.effectiveProviderId()` returns that other provider so model catalog, preflight, and CLI env stay aligned with what the CLI will actually pick up ([AuthManager.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/AuthManager.kt)).
- The two **OpenAI providers drive the `codex` backend, not the `claude` CLI**. `openai` uses an `OPENAI_API_KEY`; `openai-subscription` uses a ChatGPT sign-in (`codex login`, `~/.codex/auth.json`). Both are registered in the `AuthManager` `providers` map but are **excluded from the effective-provider env fallthrough** — see below ([AuthManager.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/AuthManager.kt), [OpenAIAuthProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/OpenAIAuthProvider.kt), [OpenAiSubscriptionAuthProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/OpenAiSubscriptionAuthProvider.kt)).
- **Codex providers are excluded from the env fallback, and an explicitly configured codex provider is authoritative.** A bare exported `OPENAI_API_KEY` will *not* auto-hijack a Claude user onto codex (codex authenticates via `codex login`, not that env var, so the signal is too weak). Conversely, when the configured provider *is* `openai` / `openai-subscription`, `effectiveProviderId()` short-circuits and returns it before the fallback runs — `OpenAiSubscriptionAuthProvider.isConfigured()` reads an async, cache-backed `codex login status` that is stale-"not signed in" on the EDT until the first probe warms, and without the short-circuit a bridge built in that window would fall back to a Claude provider yet be handed an OpenAI model id, producing "API Error (gpt-5.x): 400 invalid model identifier" ([AuthManager.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/AuthManager.kt)).
- `AuthManager.preflight()` runs against the **effective** provider, not the configured one. This means a user with `ANTHROPIC_API_KEY` exported but "Subscription" selected in settings will still pass preflight ([AuthManager.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/AuthManager.kt)).
- `AuthManager.applyToEnvironment(env)` only writes the active provider's variables. It must run **after** `System.getenv()` is copied into the CLI env map so explicit settings override inherited shell env, but before the process is spawned ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).
- Subscription auth shells out to `claude auth login --claudeai` as a streaming subprocess. ClawDEA watches stdout for the paste-code prompt but lets the **CLI itself** open the browser — never opens `https://...` from the plugin ([SubscriptionAuth.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/SubscriptionAuth.kt)).
- The `claude` binary path is resolved through `resolveClaudeCliPath()`. IntelliJ launched from Finder/Dock does **not** inherit the user's shell PATH, so the resolver also probes common install locations (`/usr/local/bin`, `/opt/homebrew/bin`, `nvm`, `volta`) ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).

## OpenAI-compatible provider profiles

As of 2.1, ClawDEA supports **OpenAI-compatible provider profiles** for inline completions and intention actions. Each profile defines:

- A stable **profile ID** (string) used to isolate credentials, model catalogs, and pricing.
- An **API base URL** (required to be HTTPS unless localhost).
- A **model catalog** with per-model capabilities (agentic, completion-only, etc.) and pricing estimates.
- A **credential schema** and optional **declarative credential exchange flows** (e.g., OAuth2 authorization_code).

**Credential persistence**: profile credentials are stored in IntelliJ's native `PasswordSafe`, keyed by `openai-compatible/<profile-id>`. Credentials never appear in `ClawDEASettings`, exports, or on-disk files outside PasswordSafe. Interactive secrets (e.g., OAuth authorization codes) are held in memory only during the exchange flow and never persisted.

**No environment fallback**: unlike the built-in providers (which fall through to `ANTHROPIC_API_KEY` etc. from the shell env), OpenAI-compatible providers ONLY use credentials from PasswordSafe. This ensures profile isolation — two profiles with different API keys cannot accidentally use the same env-var credential.

**Declarative credential flow**: profiles can specify OAuth2 flows (authorization_code, client_credentials, etc.) via JSON config. ClawDEA executes the HTTP flows at runtime (prompting for user confirmation when required) and persists only the durable credential (e.g., refresh token). The profile itself contains NO secrets.

**Gateway routing**: when an OpenAI-compatible profile + model is selected, `ClaudeGateway.selectPath()` routes to `streamViaOpenAiCompatible()`, which sends requests to the profile's base URL with the credential from PasswordSafe. No silent fallback to Claude — if the profile's API is unreachable, the completion fails.

**Agentic chat credentials**: the agentic HTTP chat backend uses the same PasswordSafe-scoped credential. On a 401/403 the backend renews the credential exactly once via a prompt + the profile's declarative credential flow (`CredentialRenewalCoordinator` → `CredentialFlowExecutor`), then retries with the fresh credential; a second auth failure surfaces a terminal error. See [OpenAI-compatible provider](openai-compatible-provider.md) for the full backend contract.

## Resolution pipeline

1. **Configured provider** — `ClawDEASettings.state.apiProvider` is the user's choice in Settings → Tools → ClawDEA (`anthropic` | `bedrock` | `vertex` | `subscription` | `openai` | `openai-subscription`). The last two route the chat to the `codex` backend instead of the `claude` CLI.
2. **Effective provider** — `AuthManager.effectiveProviderId()`:
   - if the configured provider is a codex provider (`openai` / `openai-subscription`) → return it unchanged (authoritative; short-circuits the fallback)
   - else if the configured provider's `isConfigured()` returns true → use it
   - else if any **other non-codex** provider's `isConfigured()` returns true → use that one (codex providers are never fallback candidates)
   - else → fall back to the configured id (so preflight produces the right "not configured" message)
3. **Active provider** — `AuthManager.activeProvider()` looks up the effective id in the `providers` map, defaulting to `anthropic` if missing.
4. **Apply to env** — `activeProvider().applyToEnvironment(env)` writes the credential vars:
   - `anthropic` → `ANTHROPIC_API_KEY`; `bedrock` → `AWS_BEARER_TOKEN_BEDROCK` + region; `vertex` → `GOOGLE_APPLICATION_CREDENTIALS`; `subscription` → OAuth-cached tokens
   - `openai` → `OPENAI_API_KEY` (read from PasswordSafe via `ClawDEASettings.getOpenAIApiKey()`, or an inherited `OPENAI_API_KEY` shell var, which takes precedence)
   - `openai-subscription` → **removes** `OPENAI_API_KEY` from the env so a stray API key cannot shadow the ChatGPT sign-in codex reads from `~/.codex/auth.json`
5. **Preflight** — `AuthManager.preflight()` calls `activeProvider().validate()` returning `AuthValidation.Ok` or a structured failure (`MissingApiKey`, `BadCredentials`, `NetworkError`).
6. **CLI launch** — `CliProcess.preflightChecks(cliPath, env, authCheck)` blocks the spawn if preflight failed, surfacing the error to the chat panel via `CliStartException`.

## Anti-patterns

- **Using `state.apiProvider` directly to decide CLI flags or model catalog** — Always go through `AuthManager.effectiveProviderId()`. Otherwise, when the user has env-var credentials for a different provider, the catalog and preflight will desync from what the CLI actually picks up.
- **Adding OpenAI providers to the effective-provider env fallback** — Codex providers are deliberately excluded. Auto-selecting `openai` from a bare exported `OPENAI_API_KEY` would silently hijack a Claude user onto the codex backend on a weak signal (codex signs in via `codex login`, not that env var). Require *explicit* selection for codex.
- **Letting a stray `OPENAI_API_KEY` shadow a ChatGPT sign-in** — `OpenAiSubscriptionAuthProvider.applyToEnvironment` intentionally *removes* `OPENAI_API_KEY`; don't "helpfully" set it, or codex will use the API key instead of the subscription in `~/.codex/auth.json`.
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
- [OpenAIAuthProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/OpenAIAuthProvider.kt) — `OPENAI_API_KEY` provider (codex backend); env var takes precedence over the PasswordSafe-stored key
- [OpenAiSubscriptionAuthProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/OpenAiSubscriptionAuthProvider.kt) — ChatGPT subscription provider (codex backend); "configured" == `codex login status` signed in, and it removes `OPENAI_API_KEY` from the CLI env
- [SubscriptionAuth.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/SubscriptionAuth.kt) — interactive `claude auth login --claudeai` driver
- [SubscriptionAuthProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/SubscriptionAuthProbe.kt) — `claude auth status` probe
- [OpenAiCompatibleAuthProvider.kt](../../../src/main/kotlin/com/adobe/clawdea/provider/openai/auth/OpenAiCompatibleAuthProvider.kt) — profile-scoped PasswordSafe credential provider
- [ProfileStore.kt](../../../src/main/kotlin/com/adobe/clawdea/provider/openai/profile/ProfileStore.kt) — profile persistence and resolution against `ClawDEASettings`
- [ProfileImportExport.kt](../../../src/main/kotlin/com/adobe/clawdea/provider/openai/profile/ProfileImportExport.kt) — profile import / export
- [ProfileValidator.kt](../../../src/main/kotlin/com/adobe/clawdea/provider/openai/profile/ProfileValidator.kt) — profile validation
- [CredentialFlowExecutor.kt](../../../src/main/kotlin/com/adobe/clawdea/provider/openai/auth/CredentialFlowExecutor.kt) — declarative OAuth2 credential exchange execution
