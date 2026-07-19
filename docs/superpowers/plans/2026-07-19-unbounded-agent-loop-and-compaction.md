# Unbounded Agent Loop & Context Compaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the OpenAI-compatible backend's round/time ceilings configurable soft limits (0 = unlimited) with indefinite-wait "continue?" checkpoints, and replace the fatal context limit with LLM-summarization compaction.

**Architecture:** `AgentLoopController` (the in-process agent loop for the OpenAI-compatible backend) gains three behaviors: (1) round/time guards that skip when the limit is `0` and otherwise call an injected `onSoftLimit` callback instead of erroring; (2) a context check that invokes a new pure `ConversationCompactor` instead of emitting a fatal error; (3) budget measurement that prefers the model's real context window and falls back to a char heuristic. The backend wires settings through, provides a blocking Continue/Stop prompt for `onSoftLimit`, and supplies the real summarize closure.

**Tech Stack:** Kotlin (JVM 21), IntelliJ Platform 2026.1, kotlinx.coroutines, JUnit4 (`runBlocking` tests), Gson (profile deserialization).

## Global Constraints

- Kotlin only, target JVM 21. No Java sources.
- Copyright header (Apache 2.0, the 11-line block present at the top of every existing `.kt`) MUST head every new file. Copy it verbatim from any existing file in the same package.
- Work happens in the worktree `/Users/spopescu/Work/aem/ClawDEA/.claude/worktrees/openai-compatible`. All paths below are relative to it.
- `AgentLoopController` stays pure of IntelliJ/coroutine-scope concerns (injected callbacks only) so it remains unit-testable with fakes.
- Default ceilings are `0 = unlimited`. The `0 = unlimited` interpretation lives ONLY in `AgentLoopController`, never in callers.
- Compaction preserves the OpenAI message-structure invariant: every `assistant` message with `toolCalls` is immediately followed by one `tool` (role) message per call id. Never leave a dangling pair.
- Build check: `./gradlew compileKotlin`. Unit tests: `./gradlew test --tests '<fqn>'`. Pass `-x buildSearchableOptions` for a full build while the IDE is running.
- Char fallback base constant `CHAR_FALLBACK = 1_000_000` (today's `maxContextChars` value).

---

### Task 1: Settings fields

**Files:**
- Modify: `src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt` (the `data class State` block, near the OpenAI-compatible fields)

**Interfaces:**
- Produces: `ClawDEASettings.getInstance().state.agentMaxToolRounds: Int` (default `0`), `agentMaxElapsedMinutes: Int` (default `0`), `agentContextCompactionThreshold: Double` (default `0.8`).

- [ ] **Step 1: Add the three fields to `State`**

In `ClawDEASettings.kt`, inside `data class State(...)`, add near the other agent/completions knobs (e.g. right after `var chatTokenBudget: Int = 16384,`):

```kotlin
        // OpenAI-compatible agent loop limits. 0 = unlimited (loop runs until the model stops
        // calling tools or the user aborts). Non-zero re-arms an indefinite-wait "continue?"
        // checkpoint every N rounds / after M minutes. See
        // docs/superpowers/specs/2026-07-19-unbounded-agent-loop-and-compaction-design.md.
        var agentMaxToolRounds: Int = 0,
        var agentMaxElapsedMinutes: Int = 0,
        // Fraction of the context budget at which the agent loop compacts (summarizes) history.
        // Applies to the token budget when the model's context window is known, else to CHAR_FALLBACK.
        var agentContextCompactionThreshold: Double = 0.8,
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. (State is a plain settings bean; no test needed for field addition — persistence is IntelliJ-provided.)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt
git commit -m "feat(settings): add agent loop limit + compaction threshold settings"
```

---

### Task 2: Profile context-window map + lookup helper

**Files:**
- Modify: `src/main/kotlin/com/adobe/clawdea/provider/openai/profile/OpenAiCompatibleProfile.kt` (add `contextWindows` field)
- Create: `src/main/kotlin/com/adobe/clawdea/provider/openai/agent/ContextWindows.kt`
- Test: `src/test/kotlin/com/adobe/clawdea/provider/openai/agent/ContextWindowsTest.kt`

**Interfaces:**
- Consumes: `OpenAiCompatibleProfile.contextWindows: Map<String, Int>` (model id → window in tokens).
- Produces: `ContextWindows.forModel(profile: OpenAiCompatibleProfile, modelId: String): Int?` — the window for `modelId`, or `null` when unknown/non-positive.

- [ ] **Step 1: Add the profile field**

In `OpenAiCompatibleProfile.kt`, add to the `OpenAiCompatibleProfile` data class right after `val pricing: Map<String, TokenRates> = emptyMap(),`:

```kotlin
    // Per-model context window in tokens, keyed by model id (mirrors [pricing]). Empty/absent means
    // the window is unknown for that model; the agent loop then falls back to a char-based compaction
    // budget. Gson deserialization of profiles lacking the field yields an empty map.
    val contextWindows: Map<String, Int> = emptyMap(),
```

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/com/adobe/clawdea/provider/openai/agent/ContextWindowsTest.kt`:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.provider.openai.agent

import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextWindowsTest {
    @Test
    fun `returns window for known model`() {
        val profile = OpenAiCompatibleProfile(contextWindows = mapOf("qwen-max" to 32768))
        assertEquals(32768, ContextWindows.forModel(profile, "qwen-max"))
    }

    @Test
    fun `returns null for unknown model`() {
        val profile = OpenAiCompatibleProfile(contextWindows = mapOf("qwen-max" to 32768))
        assertNull(ContextWindows.forModel(profile, "other-model"))
    }

    @Test
    fun `returns null for non-positive window`() {
        val profile = OpenAiCompatibleProfile(contextWindows = mapOf("bad" to 0))
        assertNull(ContextWindows.forModel(profile, "bad"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests 'com.adobe.clawdea.provider.openai.agent.ContextWindowsTest'`
Expected: FAIL — compilation error, `ContextWindows` unresolved.

- [ ] **Step 4: Implement `ContextWindows`**

Create `src/main/kotlin/com/adobe/clawdea/provider/openai/agent/ContextWindows.kt`:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.provider.openai.agent

import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile

/** Resolves a model's context window (tokens) from profile config. Null when unknown. */
object ContextWindows {
    fun forModel(profile: OpenAiCompatibleProfile, modelId: String): Int? =
        profile.contextWindows[modelId]?.takeIf { it > 0 }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'com.adobe.clawdea.provider.openai.agent.ContextWindowsTest'`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/adobe/clawdea/provider/openai/profile/OpenAiCompatibleProfile.kt \
        src/main/kotlin/com/adobe/clawdea/provider/openai/agent/ContextWindows.kt \
        src/test/kotlin/com/adobe/clawdea/provider/openai/agent/ContextWindowsTest.kt
git commit -m "feat(openai): per-model context window map + lookup helper"
```

---

### Task 3: `ConversationCompactor` (pure)

**Files:**
- Create: `src/main/kotlin/com/adobe/clawdea/provider/openai/agent/ConversationCompactor.kt`
- Test: `src/test/kotlin/com/adobe/clawdea/provider/openai/agent/ConversationCompactorTest.kt`

**Interfaces:**
- Consumes: `AgentMessage(role, content, toolCalls, toolCallId)` and `AgentToolCall(id, name, argumentsJson)` from `AgentCompletionModels.kt`.
- Produces:
  - `class ConversationCompactor(summarize: suspend (List<AgentMessage>) -> String)`
  - `suspend fun ConversationCompactor.compact(messages: List<AgentMessage>, keepTailTarget: Int): CompactionResult`
  - `data class CompactionResult(val messages: List<AgentMessage>, val evictedToolCallIds: Set<String>, val summarizedCount: Int)`

**Design notes for the implementer (message-structure rules):**
- The FIRST message is the system prefix (role `"system"`); it is always preserved verbatim as element 0.
- A "clean boundary" index `i` (into the non-system remainder) is one where `messages[i].role == "user"`, OR `messages[i].role == "assistant"` with `toolCalls.isEmpty()`. Starting a tail there can never orphan a `tool` result (a `tool` message only ever follows an assistant-with-toolCalls).
- Tail selection: starting from `max(0, remainder.size - keepTailTarget)`, scan FORWARD to the first clean boundary; everything from there on is the verbatim tail. If no clean boundary exists at/after that index, the tail is empty (summarize everything).
- The summarized span = the non-system messages BEFORE the tail. Its evicted ids = every `toolCall.id` on assistant messages in that span (these calls are now prose).
- Rebuild: `[system] + [AgentMessage(role="user", content=summaryText)] + tail`. Use role `"user"` for the summary so no provider rejects an unexpected role and it never looks like a tool-call turn.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/adobe/clawdea/provider/openai/agent/ConversationCompactorTest.kt`:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.provider.openai.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactorTest {

    private fun sys() = AgentMessage(role = "system", content = "system prompt")
    private fun user(t: String) = AgentMessage(role = "user", content = t)
    private fun asstTool(id: String) =
        AgentMessage(role = "assistant", toolCalls = listOf(AgentToolCall(id, "read", "{}")))
    private fun toolRes(id: String) = AgentMessage(role = "tool", content = "result", toolCallId = id)

    private val compactor = ConversationCompactor(summarize = { "SUMMARY" })

    @Test
    fun `keeps system prefix and appends summary before tail`() = runBlocking {
        val msgs = listOf(sys(), user("goal"), asstTool("c1"), toolRes("c1"), user("next question"))
        val result = compactor.compact(msgs, keepTailTarget = 1)

        assertEquals("system", result.messages.first().role)
        assertEquals("SUMMARY", result.messages[1].content)
        assertEquals("user", result.messages[1].role)
        assertEquals("next question", result.messages.last().content)
    }

    @Test
    fun `never starts tail between assistant toolCalls and its tool result`() = runBlocking {
        // keepTailTarget=1 would naively start at the last message (the tool result), orphaning it.
        // The compactor must walk forward to the next clean boundary instead.
        val msgs = listOf(sys(), user("goal"), asstTool("c1"), toolRes("c1"))
        val result = compactor.compact(msgs, keepTailTarget = 1)

        // No tail message may be a bare tool result whose assistant parent was summarized away.
        val tail = result.messages.drop(2) // after [system, summary]
        assertFalse(tail.any { it.role == "tool" && it.toolCallId == "c1" })
    }

    @Test
    fun `evicts summarized tool call ids`() = runBlocking {
        val msgs = listOf(sys(), user("goal"), asstTool("c1"), toolRes("c1"), user("q2"))
        val result = compactor.compact(msgs, keepTailTarget = 1)
        assertTrue(result.evictedToolCallIds.contains("c1"))
    }

    @Test
    fun `empty tail summarizes everything when no clean boundary in window`() = runBlocking {
        // Only message after system is a tool result / assistant-with-tools: no clean tail boundary.
        val msgs = listOf(sys(), asstTool("c1"), toolRes("c1"))
        val result = compactor.compact(msgs, keepTailTarget = 1)
        assertEquals("system", result.messages[0].role)
        assertEquals("SUMMARY", result.messages[1].content)
        assertEquals(2, result.messages.size) // system + summary, no tail
        assertTrue(result.evictedToolCallIds.contains("c1"))
    }

    @Test
    fun `summarize failure propagates`() = runBlocking {
        val failing = ConversationCompactor(summarize = { throw IllegalStateException("boom") })
        var threw = false
        try {
            failing.compact(listOf(sys(), user("a"), user("b")), keepTailTarget = 1)
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.adobe.clawdea.provider.openai.agent.ConversationCompactorTest'`
Expected: FAIL — `ConversationCompactor` unresolved.

- [ ] **Step 3: Implement `ConversationCompactor`**

Create `src/main/kotlin/com/adobe/clawdea/provider/openai/agent/ConversationCompactor.kt`:

```kotlin
/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.provider.openai.agent

/** Result of a compaction pass: the rebuilt history and the tool-call ids that were summarized away. */
data class CompactionResult(
    val messages: List<AgentMessage>,
    val evictedToolCallIds: Set<String>,
    val summarizedCount: Int,
)

/**
 * Pure conversation compactor. Summarizes all prior history into one message and keeps a verbatim
 * tail that begins at a clean boundary, so the OpenAI message-structure invariant (assistant
 * toolCalls immediately followed by their tool results) is never violated.
 *
 * [summarize] is injected (one completion call in production, a fake in tests). It receives the
 * span being summarized and returns prose. Exceptions propagate to the caller unchanged.
 */
class ConversationCompactor(
    private val summarize: suspend (List<AgentMessage>) -> String,
) {
    suspend fun compact(messages: List<AgentMessage>, keepTailTarget: Int): CompactionResult {
        if (messages.isEmpty()) return CompactionResult(messages, emptySet(), 0)

        // Element 0 is the system prefix (preserved verbatim). Everything else is the remainder.
        val system = messages.first()
        val remainder = messages.drop(1)

        val tailStart = cleanBoundaryAtOrAfter(remainder, (remainder.size - keepTailTarget).coerceAtLeast(0))
        val span = if (tailStart == null) remainder else remainder.subList(0, tailStart)
        val tail = if (tailStart == null) emptyList() else remainder.subList(tailStart, remainder.size)

        val summaryText = summarize(span)
        val evicted = span.flatMap { it.toolCalls }.map { it.id }.toSet()

        val rebuilt = buildList {
            add(system)
            add(AgentMessage(role = "user", content = summaryText))
            addAll(tail)
        }
        return CompactionResult(rebuilt, evicted, span.size)
    }

    /** First index >= [from] whose message is a clean boundary (user, or assistant w/o toolCalls). Null if none. */
    private fun cleanBoundaryAtOrAfter(messages: List<AgentMessage>, from: Int): Int? {
        for (i in from until messages.size) {
            val m = messages[i]
            if (m.role == "user") return i
            if (m.role == "assistant" && m.toolCalls.isEmpty()) return i
        }
        return null
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.adobe.clawdea.provider.openai.agent.ConversationCompactorTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/adobe/clawdea/provider/openai/agent/ConversationCompactor.kt \
        src/test/kotlin/com/adobe/clawdea/provider/openai/agent/ConversationCompactorTest.kt
git commit -m "feat(openai): boundary-safe ConversationCompactor"
```

---

### Task 4: `AgentLoopController` — soft limits + checkpoint + compaction hook

**Files:**
- Modify: `src/main/kotlin/com/adobe/clawdea/provider/openai/agent/AgentLoopController.kt`
- Modify: `src/test/kotlin/com/adobe/clawdea/provider/openai/agent/AgentLoopControllerTest.kt`

**Interfaces:**
- Consumes: `ConversationCompactor` (Task 3), `AgentUsage` (`state.usage.inputTokens` etc.).
- Produces (new `AgentLoopController` constructor params, appended after the existing ones so current call sites that use named args still compile):
  - `onSoftLimit: suspend (SoftLimitReason, Int) -> Boolean = { _, _ -> true }`
  - `compactor: ConversationCompactor? = null`
  - `contextWindowTokens: Int? = null`
  - `compactionThreshold: Double = 0.8`
  - `onCompacted: (Int) -> Unit = {}` (notice callback: arg = summarized message count)
  - `enum class SoftLimitReason { ROUNDS, TIME }`
- Behavior change: `maxToolRounds` / `maxElapsedMs` of `0` mean "no limit". Non-zero triggers `onSoftLimit`; a `false` return emits a terminal `Result` with `isError = false` and ends the turn; a `true` return resets the tripped counter and continues. Context over-threshold triggers compaction (when `compactor != null`) instead of the fatal error; if `compactor == null` OR summarization throws, the old fatal "Context budget exceeded" result is emitted.

**Design notes for the implementer:**
- `SoftLimitReason` + `enum` go at the top of `AgentLoopController.kt` (same file, above the class).
- Round guard: replace the block at `AgentLoopController.kt:296` (`if (toolRounds >= maxToolRounds) { emit(... "Tool round limit exceeded" ...) ; return }`) with the checkpoint logic below. Keep the `toolRounds++` after it.
- Time guard: the check at `:119`. Convert `maxElapsedMs == 0` to "skip". On trip, call the shared `checkpoint(...)`.
- Context guard: the check at `:137`. Compute the budget, compare to threshold, compact-or-fail.
- Time/round counters "reset" means: for ROUNDS, set `toolRounds = 0`; for TIME, set `turnStart = System.currentTimeMillis()` (make `turnStart` a `var`).
- The terminal `Result` for a `false` checkpoint uses `isError = false` and `text = state.partialAssistantText` (a clean user-initiated stop).

- [ ] **Step 1: Write failing tests**

Add these tests to `AgentLoopControllerTest.kt` (keep the existing two; the second one is REPLACED — see Step 5). Add imports `import kotlinx.coroutines.flow.flow` if needed.

```kotlin
    @Test
    fun `zero round limit never checkpoints and runs to natural end`() = runBlocking {
        val executor = CountingToolExecutor()
        val state = ConversationState()
        // Client returns a tool call the first 4 rounds, then no tool calls (natural end).
        var round = 0
        val client = FakeAgentClient {
            round++
            if (round <= 4) flowOf(
                AgentStreamEvent.ToolFragment(0, "call-$round", "test_tool", "{}"),
                AgentStreamEvent.Finished("tool_calls"),
            ) else flowOf(
                AgentStreamEvent.Text("done"),
                AgentStreamEvent.Finished("stop"),
            )
        }
        var checkpoints = 0
        val loop = AgentLoopController(
            client = client, executor = executor, state = state,
            maxToolRounds = 0, maxElapsedMs = 0, maxContextChars = 1_000_000,
            onSoftLimit = { _, _ -> checkpoints++; true },
        )
        val result = loop.runTurn("work") {}
        assertEquals(0, checkpoints)
        assertFalse(result.isError)
    }

    @Test
    fun `non-zero round limit checkpoints at N and 2N`() = runBlocking {
        val executor = CountingToolExecutor()
        val state = ConversationState()
        var round = 0
        val client = FakeAgentClient {
            round++
            flowOf(
                AgentStreamEvent.ToolFragment(0, "call-$round", "test_tool", "{}"),
                AgentStreamEvent.Finished("tool_calls"),
            )
        }
        val counts = mutableListOf<Int>()
        // Continue at the first checkpoint, stop at the second.
        val loop = AgentLoopController(
            client = client, executor = executor, state = state,
            maxToolRounds = 2, maxElapsedMs = 0, maxContextChars = 1_000_000,
            onSoftLimit = { _, count -> counts.add(count); counts.size < 2 },
        )
        val result = loop.runTurn("work") {}
        assertEquals(listOf(2, 2), counts) // fired at round 2, reset, fired again at 2
        assertFalse(result.isError)        // user-initiated stop is not an error
    }

    @Test
    fun `context over threshold compacts and continues`() = runBlocking {
        val executor = CountingToolExecutor()
        // Seed a large history so the char budget is exceeded on the first check.
        val big = "x".repeat(2000)
        val state = ConversationState(
            messages = mutableListOf(
                AgentMessage(role = "system", content = "sys"),
                AgentMessage(role = "user", content = big),
                AgentMessage(role = "user", content = big),
            )
        )
        var round = 0
        val client = FakeAgentClient {
            round++
            if (round == 1) flowOf(AgentStreamEvent.Text("ok"), AgentStreamEvent.Finished("stop"))
            else flowOf(AgentStreamEvent.Text("ok"), AgentStreamEvent.Finished("stop"))
        }
        var compactions = 0
        val loop = AgentLoopController(
            client = client, executor = executor, state = state,
            maxToolRounds = 0, maxElapsedMs = 0,
            maxContextChars = 3000, // char fallback budget; threshold 0.8 => compact at 2400 chars
            compactor = ConversationCompactor(summarize = { "SUMMARY" }),
            compactionThreshold = 0.8,
            onCompacted = { compactions++ },
        )
        val result = loop.runTurn("continue", appendUserMessage = false) {}
        assertEquals(1, compactions)
        assertFalse(result.isError)
    }

    @Test
    fun `context over threshold with no compactor emits fatal result`() = runBlocking {
        val state = ConversationState(
            messages = mutableListOf(AgentMessage(role = "user", content = "x".repeat(5000)))
        )
        val client = FakeAgentClient(flowOf(AgentStreamEvent.Finished("stop")))
        val events = mutableListOf<com.adobe.clawdea.cli.CliEvent>()
        val loop = AgentLoopController(
            client = client, executor = CountingToolExecutor(), state = state,
            maxToolRounds = 0, maxElapsedMs = 0, maxContextChars = 3000,
            compactor = null,
        )
        val result = loop.runTurn("continue", appendUserMessage = false) { events.add(it) }
        assertTrue(result.isError)
        assertTrue(events.filterIsInstance<com.adobe.clawdea.cli.CliEvent.Result>()
            .any { it.text.contains("Context budget exceeded") })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.adobe.clawdea.provider.openai.agent.AgentLoopControllerTest'`
Expected: FAIL — new constructor params / `SoftLimitReason` unresolved; existing `loop stops after configured tool round limit` still passes for now.

- [ ] **Step 3: Add `SoftLimitReason` and new constructor params**

In `AgentLoopController.kt`, above `class AgentLoopController`:

```kotlin
/** Why a soft limit checkpoint fired. */
enum class SoftLimitReason { ROUNDS, TIME }
```

Change the constructor to append the new params (after `stream`):

```kotlin
    private val stream: Boolean = true,
    // Soft-limit checkpoint. Called when a NON-ZERO round/time ceiling trips. Returns true to
    // continue (counter resets), false to stop the turn cleanly. Default always continues.
    private val onSoftLimit: suspend (SoftLimitReason, Int) -> Boolean = { _, _ -> true },
    // When non-null, an over-threshold context triggers compaction instead of a fatal error.
    private val compactor: ConversationCompactor? = null,
    // The model's context window in tokens, or null to use the char fallback budget.
    private val contextWindowTokens: Int? = null,
    private val compactionThreshold: Double = 0.8,
    // Notice callback after a successful compaction; arg = number of messages summarized.
    private val onCompacted: (Int) -> Unit = {},
```

- [ ] **Step 4: Rewrite the three guards**

Make `turnStart` mutable — change `val turnStart = System.currentTimeMillis()` (`:102`) to `var turnStart = System.currentTimeMillis()`.

Add a private suspend helper inside the class (e.g. just below `runTurn`), which emits the clean terminal result and returns whether to continue:

```kotlin
    // Returns true if the loop should continue (counter reset by caller), false if the turn ended
    // (this method emitted the terminal Result).
    private suspend fun checkpoint(reason: SoftLimitReason, count: Int, emit: (CliEvent) -> Unit): Boolean {
        val cont = onSoftLimit(reason, count)
        if (cont) return true
        emit(CliEvent.Result(
            text = state.partialAssistantText,
            isError = false,
            costUsd = 0.0,
            sessionId = "",
            inputTokens = state.usage.inputTokens,
            outputTokens = state.usage.outputTokens,
            cacheReadTokens = state.usage.cachedInputTokens,
            cacheCreationTokens = 0,
            reasoningTokens = state.usage.reasoningTokens,
            contextWindow = 0,
        ))
        return false
    }
```

Replace the TIME guard (`:119`) body:

```kotlin
            // Check time limit (0 = unlimited)
            if (maxElapsedMs > 0 && System.currentTimeMillis() - turnStart > maxElapsedMs) {
                val elapsedMin = ((System.currentTimeMillis() - turnStart) / 60_000).toInt()
                if (!checkpoint(SoftLimitReason.TIME, elapsedMin, emit)) {
                    return TurnResult(isError = false, toolRounds = toolRounds, finalText = state.partialAssistantText)
                }
                turnStart = System.currentTimeMillis() // reset window and continue
            }
```

Replace the CONTEXT guard (`:137`) body:

```kotlin
            // Context budget: compact when over threshold (never fatal if a compactor is present).
            val contextChars = state.messages.sumOf { (it.content?.length ?: 0) }
            val overThreshold = when (val window = contextWindowTokens) {
                null -> contextChars >= maxContextChars * compactionThreshold
                else -> (state.usage.inputTokens + state.usage.outputTokens) >= window * compactionThreshold
            }
            if (overThreshold) {
                val compacted = compactor?.let { c ->
                    try {
                        val r = c.compact(state.messages.toList(), keepTailTarget = COMPACT_KEEP_TAIL)
                        state.messages.clear()
                        state.messages.addAll(r.messages)
                        state.completedToolCallIds.removeAll(r.evictedToolCallIds)
                        onCompacted(r.summarizedCount)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("compaction failed, falling back to fatal context result", e)
                        false
                    }
                } ?: false
                if (!compacted) {
                    emit(CliEvent.Result(
                        text = "Context budget exceeded",
                        isError = true,
                        costUsd = 0.0,
                        sessionId = "",
                        inputTokens = state.usage.inputTokens,
                        outputTokens = state.usage.outputTokens,
                        cacheReadTokens = state.usage.cachedInputTokens,
                        reasoningTokens = state.usage.reasoningTokens,
                        cacheCreationTokens = 0,
                        contextWindow = 0,
                    ))
                    return TurnResult(isError = true, toolRounds = toolRounds)
                }
            }
```

Replace the ROUND guard (`:296`) body:

```kotlin
            // Tool round limit (0 = unlimited). Check BEFORE incrementing.
            if (maxToolRounds > 0 && toolRounds >= maxToolRounds) {
                if (!checkpoint(SoftLimitReason.ROUNDS, toolRounds, emit)) {
                    return TurnResult(isError = false, toolRounds = toolRounds, finalText = state.partialAssistantText)
                }
                toolRounds = 0 // reset window and continue
            }
            toolRounds++
```

Add the tail-keep constant to the `companion object` (create one if absent):

```kotlin
    companion object {
        private const val COMPACT_KEEP_TAIL = 6
    }
```

- [ ] **Step 5: Update the existing round-limit test to checkpoint semantics**

Replace the body of `loop stops after configured tool round limit` so it asserts the checkpoint path (the old `isError=true` assertion no longer holds — a default `onSoftLimit` continues forever, so provide one that stops):

```kotlin
    @Test
    fun `loop stops after configured tool round limit`() = runBlocking {
        val executor = CountingToolExecutor()
        val state = ConversationState()

        var callCount = 0
        val client = FakeAgentClient {
            flowOf(
                AgentStreamEvent.ToolFragment(0, "call-${callCount++}", "test_tool", "{}"),
                AgentStreamEvent.Finished("tool_calls")
            )
        }

        var fired = 0
        val loop = AgentLoopController(
            client = client,
            executor = executor,
            state = state,
            maxToolRounds = 3,
            maxElapsedMs = 600_000,
            maxContextChars = 1_000_000,
            onSoftLimit = { _, count -> fired = count; false }, // stop at the first checkpoint
        )

        val result = loop.runTurn("work") { }

        assertEquals(3, fired)          // checkpoint saw toolRounds == 3
        assertFalse(result.isError)     // clean user-initiated stop
    }
```

Add `import org.junit.Assert.assertFalse` to the test file.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.adobe.clawdea.provider.openai.agent.AgentLoopControllerTest'`
Expected: PASS (6 tests: 1 original exactly-once + updated round-limit + 4 new).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/adobe/clawdea/provider/openai/agent/AgentLoopController.kt \
        src/test/kotlin/com/adobe/clawdea/provider/openai/agent/AgentLoopControllerTest.kt
git commit -m "feat(openai): soft-limit checkpoints + compaction hook in agent loop"
```

---

### Task 5: Backend wiring — settings, checkpoint prompt, summarize closure, notice

**Files:**
- Modify: `src/main/kotlin/com/adobe/clawdea/cli/backend/OpenAiCompatibleAgentBackend.kt`
- Test: `src/test/kotlin/com/adobe/clawdea/cli/backend/OpenAiCompatibleAgentBackendTest.kt` (existing; add a wiring test)

**Interfaces:**
- Consumes: `ClawDEASettings` fields (Task 1), `ContextWindows.forModel` (Task 2), `ConversationCompactor` (Task 3), `AgentLoopController` new params (Task 4).
- Produces: the loop at `OpenAiCompatibleAgentBackend.kt:428` is constructed with the new params wired from settings + profile; a blocking `promptSoftLimit` method; a `buildSummarizer()` closure.

**Design notes for the implementer:**
- Read settings once per turn-loop construction: `val settings = ClawDEASettings.getInstance().state`.
- `maxToolRounds = settings.agentMaxToolRounds` (pass through; the controller interprets 0). `maxElapsedMs = settings.agentMaxElapsedMinutes.toLong() * 60_000` (0 stays 0 = unlimited). `maxContextChars` stays `1_000_000` (the `CHAR_FALLBACK`). `compactionThreshold = settings.agentContextCompactionThreshold`.
- `contextWindowTokens = ContextWindows.forModel(profile.profile, currentModelId)`.
- `compactor = ConversationCompactor(summarize = buildSummarizer())`.
- `onCompacted = { n -> queue.put(CliEvent.Result-like notice) }` — but a `Result` ends the turn in the UI; instead emit an assistant-style notice. Simplest: reuse the existing `writeAssistant`/queue path with a short system note. Use `queue.put(CliEvent.TextDelta("\n_Compacted context — summarized $n earlier messages._\n"))` so it renders inline without terminating the turn.
- `onSoftLimit = { reason, count -> promptSoftLimit(reason, count) }`. `promptSoftLimit` mirrors `promptPartialRetry` (`:566`): `invokeAndWait` + `Messages.showYesNoDialog`, returns true for "Continue". Fails closed to `false` (stop) when `project == null` (headless), matching `promptPartialRetry`.

- [ ] **Step 1: Implement `buildSummarizer()` and `promptSoftLimit`**

Add to `OpenAiCompatibleAgentBackend.kt` (near `promptPartialRetry`, `:566`):

```kotlin
    /**
     * Build the summarize closure the [ConversationCompactor] uses. Issues one non-streamed
     * completion asking the model to summarize the given span, and collects its text.
     */
    private fun buildSummarizer(): suspend (List<com.adobe.clawdea.provider.openai.agent.AgentMessage>) -> String =
        { span ->
            val client = clientFactory(profile, currentCredential!!)
            val prompt = com.adobe.clawdea.provider.openai.agent.AgentMessage(
                role = "user",
                content = "Summarize the conversation so far, preserving the task goal, key " +
                    "decisions, and findings. Be concise but keep facts needed to continue the work.",
            )
            val request = com.adobe.clawdea.provider.openai.agent.AgentCompletionRequest(
                model = currentModelId,
                messages = span + prompt,
                tools = emptyList(),
                maxTokens = 1024,
                stream = false,
            )
            val sb = StringBuilder()
            client.stream(request).collect { ev ->
                if (ev is com.adobe.clawdea.provider.openai.agent.AgentStreamEvent.Text) sb.append(ev.text)
            }
            sb.toString().ifBlank { "[summary unavailable]" }
        }

    /**
     * Blocking Continue/Stop checkpoint for a non-zero soft limit. Waits indefinitely (no timeout,
     * unlike the permission-prompt path). Returns true to continue. Fails closed (false) headless.
     */
    private fun promptSoftLimit(
        reason: com.adobe.clawdea.provider.openai.agent.SoftLimitReason,
        count: Int,
    ): Boolean {
        val proj = project ?: return false
        val msg = when (reason) {
            com.adobe.clawdea.provider.openai.agent.SoftLimitReason.ROUNDS ->
                "The agent has run $count tool-call rounds. Continue?"
            com.adobe.clawdea.provider.openai.agent.SoftLimitReason.TIME ->
                "The agent has run for about $count minute(s). Continue?"
        }
        val decision = java.util.concurrent.atomic.AtomicBoolean(false)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
            val choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
                proj, msg, "ClawDEA — Continue Long Run?", "Continue", "Stop", null,
            )
            decision.set(choice == com.intellij.openapi.ui.Messages.YES)
        }
        return decision.get()
    }
```

- [ ] **Step 2: Wire the loop construction**

Replace the `AgentLoopController(...)` construction at `:428` with the settings-driven wiring:

```kotlin
        while (true) {
            val settings = com.adobe.clawdea.settings.ClawDEASettings.getInstance().state
            val loop = AgentLoopController(
                client = clientFactory(profile, currentCredential!!),
                executor = executorFactory(),
                state = state,
                maxToolRounds = settings.agentMaxToolRounds,
                maxElapsedMs = settings.agentMaxElapsedMinutes.toLong() * 60_000L,
                maxContextChars = 1_000_000,
                modelId = currentModelId,
                tools = agentToolDefinitions(mcpDefs),
                stream = profile.profile.streaming,
                onSoftLimit = { reason, count -> promptSoftLimit(reason, count) },
                compactor = com.adobe.clawdea.provider.openai.agent.ConversationCompactor(
                    summarize = buildSummarizer(),
                ),
                contextWindowTokens = com.adobe.clawdea.provider.openai.agent.ContextWindows.forModel(
                    profile.profile, currentModelId,
                ),
                compactionThreshold = settings.agentContextCompactionThreshold,
                onCompacted = { n ->
                    queue.put(CliEvent.TextDelta("\n_Compacted context — summarized $n earlier messages._\n"))
                },
            )
```

- [ ] **Step 3: Write the wiring test**

Add to `OpenAiCompatibleAgentBackendTest.kt` a test that a `0` round setting causes no checkpoint on a short run (natural end). Follow the existing test's construction pattern in that file for building the backend with fakes (client factory returning a canned flow, executor fake). Assert the turn completes with a non-error `Result` and no dialog path is hit (dialog can't show headless — `project` is null in tests, so `promptSoftLimit` returns false; with `agentMaxToolRounds = 0` it must never be called).

```kotlin
    @Test
    fun `zero round setting completes short run without checkpoint`() {
        // Build the backend with agentMaxToolRounds defaulting to 0, a client that returns a single
        // text response (no tool calls), and assert readEvent yields a non-error Result.
        // (Mirror the existing backend-test harness in this file for construction + readEvent drain.)
    }
```

> Implementer: expand the stub above using the existing harness already present in `OpenAiCompatibleAgentBackendTest.kt`. If that file has no reusable harness for a full turn, assert the narrower guarantee instead: that `ClawDEASettings.getInstance().state.agentMaxToolRounds` defaults to `0` and the construction at Step 2 compiles and runs (a smoke test), rather than fabricating a new harness.

- [ ] **Step 4: Run tests + full compile**

Run: `./gradlew test --tests 'com.adobe.clawdea.cli.backend.OpenAiCompatibleAgentBackendTest'`
Expected: PASS.
Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/adobe/clawdea/cli/backend/OpenAiCompatibleAgentBackend.kt \
        src/test/kotlin/com/adobe/clawdea/cli/backend/OpenAiCompatibleAgentBackendTest.kt
git commit -m "feat(openai): wire configurable limits + compaction into backend"
```

---

### Task 6: Settings UI + full verification

**Files:**
- Modify: the ClawDEA settings panel that renders the OpenAI-compatible / agent fields (find via `find_symbol` for the settings configurable; likely under `src/main/kotlin/com/adobe/clawdea/settings/`).

**Interfaces:**
- Consumes: `ClawDEASettings` fields from Task 1.
- Produces: three UI controls bound to `agentMaxToolRounds`, `agentMaxElapsedMinutes`, `agentContextCompactionThreshold`.

- [ ] **Step 1: Locate the settings panel**

Run (from repo root, IDE tools preferred): `find_symbol` for the settings `Configurable`, or:
`grep -rln "chatTokenBudget\|toolApprovalMode" src/main/kotlin/com/adobe/clawdea/settings/` to find the panel that renders existing State fields.

- [ ] **Step 2: Add three controls**

Follow the existing field-binding idiom in that panel (spinner/int field for the two limits, a spinner or text field for the `0.0–1.0` threshold). Label them:
- "Agent tool-call rounds before checkpoint (0 = unlimited)"
- "Agent minutes before checkpoint (0 = unlimited)"
- "Compact context at fraction of budget (0.0–1.0)"

Bind to `state.agentMaxToolRounds`, `state.agentMaxElapsedMinutes`, `state.agentContextCompactionThreshold` in the panel's `apply()`/`reset()`/`isModified()` (match how the existing fields there do it).

- [ ] **Step 3: Compile + run the full unit suite**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.
Run: `./gradlew test`
Expected: all tests green (fixture tests are excluded from this task by the repo's existing config).

- [ ] **Step 4: Manual smoke (documented, not automated)**

Run: `./gradlew runIde -x buildSearchableOptions`
In the sandbox: open Settings > Tools > ClawDEA, confirm the three fields render, persist across a settings close/reopen, and default to 0 / 0 / 0.8. (This step is verification only — no code change.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/adobe/clawdea/settings/
git commit -m "feat(settings): expose agent loop limit + compaction controls in UI"
```

---

## Self-Review

**Spec coverage:**
- Round/time soft limits, 0 = unlimited → Task 4 (guards) + Task 5 (settings passthrough) + Task 1 (fields). ✅
- Indefinite-wait checkpoint (not permission-prompt 60s cap) → Task 5 `promptSoftLimit` (`Messages.showYesNoDialog` blocks indefinitely). ✅
- Context never fatal → compaction → Task 4 context guard + Task 3 compactor. ✅
- Token-budget-when-known, char-fallback → Task 4 `overThreshold` + Task 2 `ContextWindows`. ✅
- Summarize-all + clean tail, boundary safety → Task 3. ✅
- Ledger reconciliation (evict summarized ids) → Task 4 `removeAll(r.evictedToolCallIds)` + Task 3 `evictedToolCallIds`. ✅
- Summarize-failure fallback to fatal result → Task 4 context guard catch + Task 4 test `context over threshold with no compactor emits fatal result`. ✅
- Compaction notice → Task 5 `onCompacted`. ✅
- `isError=false` on user-stop checkpoint (cost accounting) → Task 4 `checkpoint`. ✅
- Context-window data source gap (discovered) → Task 2 profile `contextWindows` map. ✅

**Placeholder scan:** Task 5 Step 3 intentionally leaves the full-turn harness test as a guided stub because the exact reusable harness in `OpenAiCompatibleAgentBackendTest.kt` isn't known without reading it at execution time; the step gives an explicit narrower fallback (smoke test) so it is never left blank. All code-bearing steps contain full code.

**Type consistency:** `CompactionResult(messages, evictedToolCallIds, summarizedCount)` is produced in Task 3 and consumed in Task 4 (`r.messages`, `r.evictedToolCallIds`, `r.summarizedCount`). `SoftLimitReason { ROUNDS, TIME }` defined in Task 4, used in Task 5. `ContextWindows.forModel(profile, modelId)` defined Task 2, called Task 4-via-5. `onSoftLimit`, `compactor`, `contextWindowTokens`, `compactionThreshold`, `onCompacted` param names match between Task 4 definition and Task 5 call site. ✅
