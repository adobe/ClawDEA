# OpenAI-Compatible Provider Profiles

**Status:** Approved design
**Date:** 2026-07-16

## Purpose

Add a generic OpenAI-compatible provider to ClawDEA without embedding any private provider names,
hosts, model IDs, onboarding instructions, or credentials in the public repository or plugin
artifact.

Provider configuration is imported and exported as versioned JSON. Organizations may distribute
private profiles independently from ClawDEA. A profile can configure an OpenAI-compatible endpoint,
model discovery, model capabilities, pricing estimates, and a constrained declarative credential
exchange.

The provider supports:

- Agentic chat with ClawDEA tools, approvals, reviewed edits, streaming, and session resume.
- Inline completions and intention actions through the same provider client.
- Completion-only models when tool support is unavailable or unknown.
- Imported pricing defaults with local per-model overrides.

## Non-goals

- Shipping organization-specific profiles in the ClawDEA repository or plugin.
- Executing scripts or arbitrary code from imported profiles.
- Silently transferring prompts to another provider.
- Claiming agentic support for models whose tool-call capability is unknown.
- Translating the OpenAI Responses API to Chat Completions for Codex app-server.

## Architecture

### Provider registry

Introduce a small `ProviderRegistry` as the source of truth for provider behavior. A provider
descriptor declares:

- Stable provider ID and display label.
- Backend kind: Claude CLI, Codex app-server, or OpenAI-compatible HTTP.
- Authentication strategy.
- Model-catalog probe.
- Cost and usage behavior.
- Support for inline completions and intention actions.

This replaces duplicated provider-ID checks in authentication, bridge selection, labels, settings,
and cost UI. Existing Claude and Codex behavior remains unchanged.

### Backend boundary

Refine `CliBridge` to depend on a backend that emits normalized `CliEvent` values rather than
constructing an `AgentProcess` and parser directly.

- Existing process/parser pairs are wrapped as Claude and Codex backends.
- `OpenAiCompatibleAgentBackend` owns HTTP streaming, conversation state, tool execution,
  cancellation, steering emulation, retry, and session persistence.
- `OpenAiCompatibleClient` owns transport-neutral Chat Completions requests, model discovery, and
  ordinary completion requests used by chat, inline completion, and intention actions.

This is a targeted boundary change. It does not alter the wire protocols or lifecycle behavior of
the existing Claude and Codex backends.

### Tool catalog

Build a transport-neutral catalog from ClawDEA's existing MCP and host tools.

- Index, debugger, context, wiki, workspace, and reviewed-edit tools are exposed as OpenAI tool
  definitions.
- Shell and patch tools use the existing permission dispatcher.
- File changes continue to use the native diff reviewer and auto-accept setting.
- Permission requests retain their originating chat-tab routing.

Only tools supported by both ClawDEA and the selected model are included in a request.

## Provider Profile Format

Profiles use a versioned JSON schema. The schema supports:

- Profile metadata and a stable profile ID.
- Base URL and relative endpoint paths.
- Model-list response mapping.
- Chat Completions request/response mapping.
- Default headers that contain no secret values.
- Declared non-secret settings and optional environment-variable bindings.
- Model capability rules and exact model overrides.
- Default per-model pricing estimates.
- A constrained declarative credential flow.

The profile format must not support scripts, shell commands, dynamic code, filesystem reads, or
unrestricted expression evaluation.

### Import

1. Parse and validate the JSON without making network requests.
2. Reject unknown schema versions, unsupported operations, invalid placeholders, and unsafe
   endpoint schemes.
3. Show an import preview containing:
   - Provider name and description.
   - Every host that may receive a request.
   - Every credential type that will be requested.
   - Environment-variable names.
   - Persisted non-secret fields.
4. Persist the profile only after explicit confirmation.

HTTPS is required for remote endpoints. Plain HTTP is permitted only for explicitly confirmed
localhost development profiles.

### Export

Two export modes are available:

- **Template:** profile structure and defaults, excluding user-entered values.
- **Configured profile:** non-secret values and overrides, with credentials represented only by
  symbolic PasswordSafe references.

Neither mode exports secrets, temporary tokens, environment values, or in-memory credential input.

## Declarative Credential Exchange

A profile may define ordered HTTPS request steps. Each step declares:

- Method and endpoint.
- Fixed headers and approved placeholder references.
- Request-body template.
- Expected status codes.
- Response-field extraction rules.
- Whether extracted values are temporary or durable secrets.

Allowed placeholder sources are:

- Interactive secret input.
- Interactive non-secret input.
- Persisted non-secret profile settings.
- Explicitly declared environment variables.
- Values extracted by prior credential steps.

Interactive passwords and temporary tokens remain memory-only. The final provider credential is
stored directly in a profile-scoped IntelliJ PasswordSafe entry.

### Connection flow

1. Resolve required non-secret identifiers from an environment-variable override, then persisted
   settings.
2. Validate an existing provider credential using the model-list endpoint.
3. If absent or rejected, prompt for the profile-declared interactive inputs.
4. Execute the credential steps.
5. Store the resulting durable provider credential in PasswordSafe.
6. Clear temporary values and interactive secrets.

On HTTP 401 or 403, ClawDEA runs this flow again and retries the failed operation exactly once.

## Model Discovery and Capabilities

Refresh the catalog from the profile's model-list endpoint. Resolve capabilities in this order:

1. Explicit capability metadata returned by the endpoint.
2. Profile-supplied model rules and exact entries.
3. User overrides in Settings.
4. Conservative default: completion-only.

Unknown models are never presented as agentic.

Settings may offer an explicit **Verify tool support** action. It sends a harmless no-op tool
request and records the result. ClawDEA does not probe models automatically or incur hidden usage.

## Agent Loop

For each agentic turn:

1. Assemble standing instructions, project primer, conversation history, and capability-approved
   tools.
2. Start a streaming Chat Completions request.
3. Emit text as `CliEvent.TextDelta`.
4. Emit provider reasoning summaries as `CliEvent.ReasoningDelta` when the response includes a
   supported reasoning field.
5. Accumulate streamed tool-call names and arguments.
6. Validate completed tool calls, obtain permission where required, execute them, and append their
   results to conversation state.
7. Continue until final text, cancellation, or a safety limit.

Malformed or incomplete tool calls produce structured tool errors. Agent loops enforce limits for
tool rounds, elapsed time, and context size.

### Mid-turn steering

Chat Completions cannot inject input into an active generation. ClawDEA emulates steering:

1. Cancel the active HTTP stream.
2. Preserve valid partial assistant text.
3. Discard any incomplete streamed tool call.
4. Append the steering instruction as a user message.
5. Immediately start a continuation request in the same visible turn.
6. Keep the activity and reasoning UI live during the transition.

## Sessions

Persist OpenAI-compatible sessions as versioned JSONL in a per-user ClawDEA data directory, never
in the project repository. A ledger records:

- Profile ID, project identity, model, and timestamps.
- User and assistant messages.
- Completed tool calls and tool results.
- Reasoning summaries when available.
- Token usage and configured cost estimates.

The session catalog labels entries with the imported profile name.

- Same-profile resume reconstructs the full tool-aware conversation.
- Cross-backend resume uses text-only transcript handoff.
- Incomplete or corrupt trailing records are ignored without discarding earlier valid turns.

## Settings UX

The public provider is named **OpenAI-compatible**. Its Settings card includes:

- Imported-profile selector.
- Import, export template, export configured profile, and remove actions.
- Generated non-secret fields declared by the selected profile.
- Credential status and connect/reconnect action.
- Advanced endpoint overrides.
- Model catalog refresh.
- Per-model enablement, capability, verification, and pricing controls.

No private provider labels, links, defaults, screenshots, or fixtures are included in public
resources.

## Pricing and Usage

Each model supports editable estimated rates per million:

- Input tokens.
- Output tokens.
- Cached-input tokens.
- Reasoning tokens.

Rates are stored in USD to integrate with existing Cost Control totals. The UI labels them as
configured estimates rather than provider-reported billing. Profile defaults are overridden by
local user edits.

## Failure Handling

- Schema and import failures show field-level diagnostics.
- Credential-flow response shapes and status codes are validated.
- HTTP 429 honors `Retry-After` within a bounded delay.
- Connection and 5xx failures retry automatically only before response content or tool execution.
- After partial output, preserve the transcript and ask before retrying.
- Record completed tool-call IDs so reconnects cannot execute them twice.
- Never silently fall back to another provider.
- If another provider is configured, offer an explicit fallback that identifies which conversation
  text will be transferred.

Logs may contain profile ID, destination host, status code, latency, model, and correlation ID.
They must not contain prompts, credentials, temporary tokens, tool-result bodies, or complete URLs
with query parameters.

## Testing

### Unit tests

- Provider registry and backend selection.
- Profile schema/version validation.
- Import/export round trips and secret redaction.
- Credential placeholder validation and response extraction.
- Model-list parsing and capability precedence.
- Pricing defaults and local overrides.
- SSE parsing, reasoning fields, and streamed tool-call assembly.
- Steering cancellation and continuation state.
- Session-ledger persistence and corruption recovery.

### Integration tests

Use a local OpenAI-compatible fixture server to test:

- Chat, inline completions, and intention actions.
- Model refresh and capability metadata.
- Multi-round tool execution and permission denial.
- Edit review and shell approval.
- Authentication renewal and one-time retry.
- Rate limiting and pre-output retry.
- Partial-output failure without duplicated tools.
- Native profile resume and cross-backend transcript replay.

### Privacy guard

Add a source/resource test for the generic provider implementation. It rejects private preset
hosts, names, model IDs, and onboarding data. Private profiles are tested and distributed outside
the ClawDEA repository.

## Compatibility and Migration

- Existing provider IDs and persisted catalogs remain valid.
- Existing Claude and Codex sessions retain their current scanners and native resume behavior.
- Provider switches still rebuild the active chat session when the backend kind changes.
- Imported profiles use stable IDs so renaming a profile does not orphan credentials or sessions.
- Removing a profile requires confirmation and offers independent deletion of its PasswordSafe
  credential and local session ledgers.
