# OpenAI-Compatible Provider Profiles

An **OpenAI-compatible provider profile** is a JSON document that teaches ClawDEA how to talk to any Chat-Completions-style API — a self-hosted model server, an internal gateway, or a public API that isn't Anthropic or OpenAI. This page is a builder/reference guide for the profile format itself. For how to *use* a profile once imported — settings UI walkthrough, agentic chat, cost estimates, provider fallback — see [OpenAI-compatible provider profiles](user-guide.md#openai-compatible-provider-profiles) in the User Guide.

Two real profiles ship in this repo for reference:

- [`profiles/nvidia-nim.profile.json`](../profiles/nvidia-nim.profile.json) — a minimal, working profile with no credential exchange flow (a plain API key).
- [`src/test/resources/openai-compatible/minimal-profile.json`](../src/test/resources/openai-compatible/minimal-profile.json) — a fuller fixture exercising a declarative POST-login credential exchange with a response-body extraction.

## Why a profile instead of hardcoded provider support

ClawDEA ships native support for Anthropic and OpenAI. Everything else — vLLM, Ollama behind a gateway, NVIDIA NIM, an internal LLM proxy — varies in base URL, auth scheme, and model listing format. A profile captures exactly that variance in one importable JSON file instead of a plugin release:

- **No secrets in the file.** A profile describes *how* to authenticate (a static key, or a declarative HTTP login flow), never the credential itself. Secrets live only in the IDE's PasswordSafe once you supply them.
- **Two distribution shapes.** A **template** profile (no credentials) is safe to commit to source control or hand to teammates — each person supplies their own key. A **configured export** additionally carries the credential *schema* (still no secret values) plus whatever non-secret settings you filled in, so a teammate only has to supply the secret parts.
- **Declarative, not code.** Adding a new OpenAI-compatible provider is authoring JSON, not a Kotlin PR.

## Where the schema lives

The canonical schema is the Kotlin data class `OpenAiCompatibleProfile` (`src/main/kotlin/com/adobe/clawdea/provider/openai/profile/OpenAiCompatibleProfile.kt`), parsed with Gson (fields absent from older profiles fall back to their defaults) and enforced at import time by `ProfileValidator` (`.../profile/ProfileValidator.kt`). Anything below that contradicts those two files, trust the source over this page.

## Anatomy of a profile

```json
{
  "schemaVersion": 1,
  "id": "nvidia-nim",
  "name": "NVIDIA NIM",
  "description": "NVIDIA API Catalog (build.nvidia.com) — OpenAI-compatible serverless inference.",
  "baseUrl": "https://integrate.api.nvidia.com",
  "endpoints": {
    "models": "/v1/models",
    "chatCompletions": "/v1/chat/completions"
  },
  "headers": {
    "Content-Type": "application/json"
  },
  "settings": [],
  "credentialFlow": {
    "inputs": [
      { "id": "apiKey", "label": "NVIDIA API key (nvapi-…)", "secret": true }
    ],
    "steps": [],
    "durableCredential": "${input:apiKey}"
  },
  "modelMapping": {
    "arrayPath": "$.data",
    "idPath": "$.id",
    "displayNamePath": "$.id"
  },
  "modelRules": [
    { "pattern": "z-ai/glm-5.2", "capability": "agentic" }
  ],
  "contextWindows": {
    "z-ai/glm-5.2": 1000000
  },
  "streaming": true,
  "pricing": {
    "z-ai/glm-5.2": {
      "inputPerM": 0.0, "outputPerM": 0.0, "cachedInputPerM": 0.0, "reasoningPerM": 0.0
    }
  }
}
```

### Top-level fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `schemaVersion` | int | yes | Must be the exact integer `1`. The only version ClawDEA currently understands. |
| `id` | string | yes | Stable identifier. Must match `[a-z0-9][a-z0-9._-]{2,63}` — lowercase, 3–64 chars. This is the isolation key for credentials, model catalog, pricing, and settings (see [Profile isolation](user-guide.md#profile-isolation)); **never change it** after distributing a profile, or every importer's stored state orphans. |
| `name` | string | yes | Display name shown in the Providers tab's profile dropdown. |
| `description` | string | yes | Shown in the import preview dialog. Put setup instructions here (where to get a key, rate limits, etc.) — it's the first thing a user sees before confirming import. |
| `baseUrl` | string | yes | Must resolve to a valid absolute URL with host and scheme. See [HTTPS and localhost rules](#https-and-localhost-rules). |
| `endpoints` | object | yes | `models` and `chatCompletions`, resolved *relative to* `baseUrl` (so `/v1/chat/completions` against `https://api.example.com` resolves to `https://api.example.com/v1/chat/completions`). |
| `headers` | map\<string,string\> | yes (may be `{}`) | Extra HTTP headers sent with every request. **Static values only** — see the [placeholder caveat](#the-headers-placeholder-caveat) below; don't put a secret or a `${setting:...}` reference here expecting it to resolve. |
| `settings` | array | yes (may be `[]`) | Non-secret, user-configurable values (tenant ID, region, deployment name, …). See [Settings](#settings-non-secret-configurable-values). |
| `credentialFlow` | object | yes | How ClawDEA obtains the durable credential used to authenticate. See [Credential flow](#credential-flow-getting-the-durable-credential). |
| `modelMapping` | object | yes | JSONPath-style expressions for parsing the models-list response. See [Model mapping](#model-mapping-parsing-the-models-response). |
| `modelRules` | array | yes (may be `[]`) | Glob-pattern → capability rules, the last-resort tier in capability resolution. See [Model capability](#model-capability-agentic-vs-completion-only). |
| `pricing` | map\<string, rates\> | yes (may be `{}`) | Per-model token rates for cost estimates. Keys not present here just show no cost estimate — not an error. |
| `contextWindows` | map\<string, int\> | no | Per-model context window in tokens, keyed by model id. Added in 3.0 for the agent loop's compaction checkpoints; omit and it falls back to a conservative default. |
| `streaming` | boolean | no (default `true`) | Set `false` for a gateway that mishandles `stream:true` (e.g. returns HTTP 200 with a non-SSE error body instead of a proper error status). |

Every field that's schema-required must still be present as its "empty" JSON shape (`{}`/`[]`) — `null` for a required object/array fails validation with a diagnostic naming that exact field, rather than silently defaulting.

### Settings (non-secret configurable values)

`settings` covers values that aren't secrets but do vary per user or per deployment — a tenant ID, an Azure-style deployment name, a region code:

```json
{
  "id": "tenant",
  "label": "Tenant ID",
  "environmentVariable": "EXAMPLE_TENANT",
  "required": true,
  "defaultValue": ""
}
```

- `id` — referenced elsewhere in the profile as `${setting:tenant}`. Must match `[A-Za-z_][A-Za-z0-9_.-]*` and be globally unique against every other declared id in the profile (settings, credential inputs, credential steps, and step extractions all share one namespace — see [Placeholder reference](#placeholder-reference)).
- `label` — shown next to the input field ClawDEA renders dynamically in the Providers tab when this profile is selected.
- `environmentVariable` — optional. If set and that env var is non-blank at resolve time, its value wins over whatever the user typed into the field (see resolution order in `ProfileStore.resolveConfiguredValues`).
- `required` / `defaultValue` — `defaultValue` is the fallback when neither the environment variable nor a user-entered value is present.

Resolution order at request time: **environment variable → value the user typed in the settings card → `defaultValue`**.

### Credential flow: getting the durable credential

Every profile ends up with one **durable credential** string, stored in the IDE's PasswordSafe under a profile-scoped key, and sent as `Authorization: Bearer <credential>` on every request. There are two ways to get there:

**1. A plain API key (no HTTP flow).** Declare one secret input and point `durableCredential` straight at it:

```json
"credentialFlow": {
  "inputs": [
    { "id": "apiKey", "label": "NVIDIA API key (nvapi-…)", "secret": true }
  ],
  "steps": [],
  "durableCredential": "${input:apiKey}"
}
```

Clicking **Connect** on the settings card prompts for `apiKey`, and the durable credential *is* that value verbatim — no network call. This is the NVIDIA NIM profile's whole flow.

**2. A declarative HTTP login.** Declare `inputs` (what to prompt the user for), then one or more `steps` — each a plain HTTP call (no OAuth redirect/PKCE support — just a request/response exchange) whose response can extract values for later steps or for the final `durableCredential`:

```json
"credentialFlow": {
  "inputs": [
    { "id": "account", "label": "Account", "secret": false },
    { "id": "password", "label": "Password", "secret": true }
  ],
  "steps": [
    {
      "id": "login",
      "method": "POST",
      "path": "/auth/login",
      "headers": { "Content-Type": "application/json" },
      "body": "{\"account\":\"${input:account}\",\"password\":\"${input:password}\"}",
      "expectedStatuses": [200],
      "extracts": [
        { "name": "token", "jsonPath": "$.token", "durable": true }
      ]
    }
  ],
  "durableCredential": "${step:token}"
}
```

Each step:
- `method` — `GET` or `POST` only.
- `path` — resolved relative to `baseUrl`, same as the top-level endpoints.
- `headers` / `body` — may reference `${input:...}`, `${setting:...}`, `${env:...}`, and (for steps after the first) `${step:...}` for a prior step's extraction.
- `expectedStatuses` — the step fails (throwing `CredentialFlowException`, surfaced verbatim in the Connect error dialog, e.g. `"Step login failed: expected [200], got 401"`) if the response status isn't in this list.
- `extracts` — each pulls one value out of the JSON response body via `jsonPath` (dot-path only, e.g. `$.token`, `$.data.access_token` — no array indexing or filters). Every extraction is available to later steps and to `durableCredential` via `${step:name}` regardless of `durable`; the flag only marks whether that value should be treated as sensitive by tooling around the flow (e.g. excluded from a configured export — see [Distributing a profile](#distributing-a-profile)). Set `durable: true` on anything that is itself a secret or credential-shaped value.

Steps run in order; a later step's `${step:name}` can only reference an extraction from an *earlier* step (validated — forward references are rejected at import time, not just at runtime).

If the whole flow fails partway (server error, unexpected status, missing JSON field), no partial credential is ever written — `Connect` reports the exact failing step and reason, and the user can retry or fall back to **Set API Key Manually…** to paste a credential directly into PasswordSafe, bypassing the flow entirely.

### Model mapping: parsing the models response

ClawDEA calls the `models` endpoint and needs to know how to turn the response into a model list:

```json
"modelMapping": {
  "arrayPath": "$.data",
  "idPath": "$.id",
  "displayNamePath": "$.id"
}
```

- `arrayPath` — where in the response body the array of model objects lives (`$.data` matches the standard OpenAI `{"data": [...]}` shape; use `$` if the response is a bare array).
- `idPath` / `displayNamePath` — dot-paths *within each array element* for the model's id and display label. Point both at the same field (as above) if the API has no separate display name.

All three must be valid JSON paths (`$` optionally followed by dot-separated identifiers — no bracket/array syntax). This is what **Refresh Models** on the settings card uses to populate the model table.

### Model capability: agentic vs. completion-only

A model can only drive **agentic chat** (tool calls, the full MCP toolset, the OpenAI-compatible Agent/Skill tools) if it's resolved as **agentic**. Resolution is conservative and layered, in this exact precedence (`ModelCapabilityResolver.resolve`):

1. **Explicit user override** — set via **Verify Tool Support** on the settings card (see [Verifying tool support](user-guide.md#verifying-tool-support)) or a manual capability edit in the model table. Always wins.
2. **Endpoint-declared capability** — if the models-list response itself declares a capability field ClawDEA understands.
3. **Matching `modelRules` entry** — the first pattern (in array order) that matches the model id.
4. **Default: `completion-only`.** A model with no override, no endpoint signal, and no matching rule is never assumed agentic.

`modelRules` patterns are globs, not regex — `*` for "everything after/before this point," and the whole string must match:

```json
"modelRules": [
  { "pattern": "gpt-4*", "capability": "agentic" },
  { "pattern": "*-instruct", "capability": "completion-only" }
]
```

Both the literal glob `*` and the regex-looking `.*` are treated as "match every model" (a common authoring slip, handled deliberately rather than left to silently fall through). `capability` must be `"agentic"` or `"completion_only"` — anything else resolves to `"unknown"` (never promoted to agentic).

Rules are declarative hints, not guarantees: **Verify Tool Support** is the only way to *confirm* a model can actually call tools, by sending it one harmless no-op probe function and checking whether it calls that function correctly.

### Pricing and context windows

```json
"pricing": {
  "z-ai/glm-5.2": {
    "inputPerM": 0.0, "outputPerM": 0.0, "cachedInputPerM": 0.0, "reasoningPerM": 0.0
  }
},
"contextWindows": {
  "z-ai/glm-5.2": 1000000
}
```

- `pricing` is keyed by model id, each entry giving dollars per **million** tokens for input, output, cached input, and reasoning tokens. This drives the per-turn cost footer and Cost Control panel estimates — it's a configured rate card, not real billing data. A model with no pricing entry just shows no cost estimate; that's not a validation error.
- `contextWindows` (added in 3.0) gives the agent loop a real window size per model so it checkpoints and compacts a long conversation *before* overflowing, rather than after. Omit a model and it falls back to a conservative default (currently 128K) — safe, but potentially too small for genuinely long-context models, so setting this explicitly is worth doing for anything unusual.

### Placeholder reference

Four placeholder kinds appear across `headers`, `credentialFlow.steps[].headers`, `credentialFlow.steps[].body`, and `credentialFlow.durableCredential`:

| Placeholder | Resolves to |
|---|---|
| `${input:id}` | A value the user typed into the Connect dialog for a declared `credentialFlow.inputs[].id` |
| `${setting:id}` | A resolved value for a declared `settings[].id` (env var → typed value → default, in that order) |
| `${env:NAME}` | The value of a `settings[].environmentVariable` that's actually set in the process environment |
| `${step:name}` | An earlier credential-flow step's `extracts[].name` — visible to any later step and to `durableCredential` regardless of that extraction's `durable` flag (see the [`durable` flag caveat](#credential-flow-getting-the-durable-credential)) |

All four `id`/`name` identifiers across `settings`, `credentialFlow.inputs`, `credentialFlow.steps`, and `credentialFlow.steps[].extracts` share **one global namespace** — reusing an id anywhere in that set is a validation error, even across otherwise-unrelated sections. Every placeholder reference is checked at import time against the profile's own declared ids; an unknown reference (typo, wrong kind, forward reference) fails validation with the exact path and reason, not a silent no-op.

#### The `headers` placeholder caveat

`ProfileValidator` accepts `${setting:...}`, `${env:...}`, and `${input:...}` inside the top-level `headers` map and validates the references exist — but **nothing expands them at request time**. `OpenAiCompatibleClient` sends `profile.headers` values to the HTTP client verbatim; only `credentialFlow.steps[].headers` (and `.body`) go through the expansion step (`CredentialFlowExecutor.expand`). A header value with a placeholder in it today would be sent to the server as the literal string `${setting:...}` — which will fail authentication, not succeed with the substituted value.

**Practical guidance until this is fixed:** keep every value in the top-level `headers` map a literal, static string (e.g. `"Content-Type": "application/json"`, or a fixed API-version header some gateways require). Route anything that needs to vary — API keys, tenant-specific tokens — through `credentialFlow` instead, which does resolve placeholders correctly and ends up in the `Authorization` header automatically.

### HTTPS and localhost rules

Every resolved endpoint (`baseUrl`, `endpoints.models`, `endpoints.chatCompletions`, and every credential-flow step's `path`) is checked independently:

- **`https://`** — always allowed.
- **`http://localhost`, `http://127.0.0.1`, `http://[::1]`** — allowed only with explicit confirmation (the import dialog asks; profiles resolved programmatically, e.g. via a base-URL override, are rejected rather than silently downgraded to insecure).
- **`http://` anywhere else** — always rejected. Remote endpoints must be HTTPS; there's no override for this one.

This exists to stop a profile from silently sending credentials over plaintext to a non-local host.

## Building a profile: a worked example

Say you're pointing ClawDEA at a self-hosted vLLM server behind a reverse proxy that expects a bearer token from a simple username/password exchange at `/token`.

1. **Identify the endpoints.** vLLM's OpenAI-compatible server exposes `/v1/models` and `/v1/chat/completions` off some base URL, say `https://llm.internal.example.com`.
2. **Decide the credential shape.** If it's a static API key, use the plain-input pattern (`durableCredential: "${input:apiKey}"`). If it's a login exchange, model it as a `credentialFlow.steps` entry like the fixture profile's `login` step.
3. **Write the skeleton:**

   ```json
   {
     "schemaVersion": 1,
     "id": "internal-vllm",
     "name": "Internal vLLM",
     "description": "Self-hosted vLLM behind the internal gateway. Get a token from the platform team.",
     "baseUrl": "https://llm.internal.example.com",
     "endpoints": { "models": "/v1/models", "chatCompletions": "/v1/chat/completions" },
     "headers": { "Content-Type": "application/json" },
     "settings": [],
     "credentialFlow": {
       "inputs": [
         { "id": "account", "label": "Account", "secret": false },
         { "id": "password", "label": "Password", "secret": true }
       ],
       "steps": [
         {
           "id": "login",
           "method": "POST",
           "path": "/token",
           "headers": { "Content-Type": "application/json" },
           "body": "{\"account\":\"${input:account}\",\"password\":\"${input:password}\"}",
           "expectedStatuses": [200],
           "extracts": [{ "name": "token", "jsonPath": "$.access_token", "durable": true }]
         }
       ],
       "durableCredential": "${step:token}"
     },
     "modelMapping": { "arrayPath": "$.data", "idPath": "$.id", "displayNamePath": "$.id" },
     "modelRules": [{ "pattern": "*", "capability": "agentic" }],
     "pricing": {},
     "streaming": true
   }
   ```

4. **Import and preview.** Settings → Tools → ClawDEA → Providers tab → OpenAI-compatible card → **Import Profile**. If validation fails, the error dialog names the exact JSON path and reason — fix and re-import (no need to restart the IDE).
5. **Confirm the preview dialog**, which shows the resolved hosts, the credential inputs you're about to be asked for, and any declared settings — your last chance to catch a typo'd host before it's trusted.
6. **Click Connect**, enter the account/password, and ClawDEA runs the `login` step, extracts `access_token`, and stores it in PasswordSafe.
7. **Click Refresh Models** to populate the catalog from `/v1/models`, then pick a model in the Roles tab (or the Providers tab's model table) and start chatting.
8. **If a model's capability looks wrong**, use **Verify Tool Support** rather than trusting the `modelRules` guess — it's a real signal instead of a pattern match.

## Distributing a profile

- **Template export** (`Export Template`) strips all credentials, safe to commit or hand out. Recipients each supply their own credentials on import.
- **Configured export** (`Export Configured`) keeps the credential *schema* and any non-secret `settings` values you filled in (so a teammate doesn't have to re-type a shared tenant ID, say) — but never a secret value. The durable credential itself is never included; the export carries only a `credentialRef` pointer (`passwordsafe:openai-compatible/<profile-id>`) to where it lives locally, and every credential input id and step extraction name is excluded from the configured values on top of that, as a defensive second guard.
- Either way, the plugin ships with **no pre-configured private provider profiles**. All profile data — templates or configured — is something your organization distributes and you import at runtime.

## See also

- [OpenAI-compatible provider profiles](user-guide.md#openai-compatible-provider-profiles) — the User Guide's usage-focused walkthrough (import UI, agentic chat, cost estimates, provider fallback).
- [Per-role provider selection](user-guide.md#roles-tab-per-role-provider-selection) — pointing Chat, Wiki, or Completions at a specific profile/model independently of the global provider.
- [`concepts/openai-compatible-provider.md`](llm-wiki/concepts/openai-compatible-provider.md) — the project wiki's implementation-level page (agent loop, capability gating, session ledger, pricing pipeline).
