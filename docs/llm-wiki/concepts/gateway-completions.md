# Gateway completions

**Purpose** Power latency-sensitive features (inline completions, intention actions, quick fixes) by streaming Claude responses through the fastest available path — direct Anthropic API, direct AWS Bedrock API, or the CLI subprocess fallback.

## Invariants

- Path selection is decided per-request, not per-session. `ClaudeGateway.stream()` re-checks credentials each call so that a user adding an API key at runtime gets the direct-API path on the next completion ([ClaudeGateway.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ClaudeGateway.kt)).
- Direct-API path requires a non-blank `ANTHROPIC_API_KEY` from `AnthropicAuthProvider.getApiKey()`. If the key is blank, the gateway falls through — even when the configured provider is "anthropic" — so a misconfigured user still gets answers via the CLI rather than a hard failure ([ClaudeGateway.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ClaudeGateway.kt)).
- Bedrock direct path requires both `region` and `bearerToken` to be non-blank, AND `effectiveProviderId() == "bedrock"`. Without effective-provider agreement we'd send Bedrock calls under a non-Bedrock account ([ClaudeGateway.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ClaudeGateway.kt)).
- The CLI fallback (`claude -p`) **does not share** the chat panel's CLI process. It spawns a fresh `claude -p ...` per call. Inline completions therefore do not touch chat session state and cannot interleave with user turns ([ClaudeGateway.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ClaudeGateway.kt)).
- `StreamingParser` consumes SSE events from the direct-API path; the CLI fallback path returns plain text and is wrapped into a single `StreamEvent`. Callers must not assume per-token streaming on the CLI path ([StreamingParser.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/StreamingParser.kt)).
- `ModelCatalogProbe` discovers available models by probing Anthropic, Bedrock, and Subscription endpoints **in parallel** at startup, then merges results. The catalog is what populates the model picker; never read provider model lists directly ([ModelCatalogProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ModelCatalogProbe.kt), [ModelCatalogMerger.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ModelCatalogMerger.kt)).

## Resolution pipeline

1. Caller (inline completion, intention action) builds a `GatewayRequest` with model, system prompt, messages, and max tokens.
2. `ClaudeGateway.stream(request)` selects a path:
   ```
   if anthropic provider has API key             → streamViaApi
   else if effective provider == bedrock         → streamViaBedrock
                                                   (requires region + token)
   else                                          → streamViaCli  (claude -p)
   ```
3. **Direct API**: builds an `HttpRequest` to `api.anthropic.com/v1/messages`, sets `accept: text/event-stream`, streams via `HttpClient.sendAsync`. Bytes flow through `StreamingParser.parseSse` which yields `StreamEvent` (delta, usage, error).
4. **Direct Bedrock**: same shape but to `bedrock-runtime.<region>.amazonaws.com/model/.../invoke-with-response-stream` with bearer-token auth.
5. **CLI fallback**: spawns `claude -p --output-format text --model <id> --append-system-prompt <prompt>` (with input JSON on stdin), reads stdout to completion, emits one consolidated `StreamEvent`. No streaming token-by-token.
6. Caller observes the `Flow<StreamEvent>` and renders into the editor (inline completion ghost text) or the chat panel.

## Anti-patterns

- **Reading `state.apiProvider` to pick the path** — Use `AuthManager.effectiveProviderId()` for Bedrock checks and `AnthropicAuthProvider.getApiKey()` for the API-key check. The configured provider may not match what credentials are actually available; see [Authentication](authentication.md).
- **Sharing the chat panel's `CliBridge` for completions** — Inline completions must not interleave with chat turns; spawn a fresh `claude -p` per call (it's stateless).
- **Assuming token-by-token streaming on the CLI path** — Only the direct-API path streams. UIs that rely on partial deltas to render typing animation must check the `StreamEvent.kind` and degrade gracefully on the CLI path.
- **Probing models sequentially** — `ModelCatalogProbe` runs probes in parallel because the slowest probe (typically subscription OAuth round-trip) would dominate startup. Serializing them blocks the model picker.

## Source pointers

- [ClaudeGateway.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ClaudeGateway.kt) — entry point, path selection, direct-API and CLI streaming
- [StreamingParser.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/StreamingParser.kt) — SSE parser for direct-API path
- [ApiModels.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ApiModels.kt) — `GatewayRequest`, `StreamEvent`, message DTOs
- [ModelCatalogProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ModelCatalogProbe.kt) — parallel probe orchestrator
- [AnthropicModelProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/AnthropicModelProbe.kt), [BedrockModelProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/BedrockModelProbe.kt), [SubscriptionModelProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/SubscriptionModelProbe.kt) — per-provider probes
- [ModelCatalogMerger.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ModelCatalogMerger.kt) — merges per-provider results into the picker model list
- [ModelEntry.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/ModelEntry.kt) — picker entry record
