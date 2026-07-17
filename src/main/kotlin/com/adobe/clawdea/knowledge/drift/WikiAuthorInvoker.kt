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
package com.adobe.clawdea.knowledge.drift

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.knowledge.wiki.WikiAgentsArg
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.openai.agent.AgentClient
import com.adobe.clawdea.provider.openai.agent.AgentLoopController
import com.adobe.clawdea.provider.openai.agent.AgentMessage
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.agent.ConversationState
import com.adobe.clawdea.provider.openai.agent.OpenAiToolDefinition
import com.adobe.clawdea.provider.openai.catalog.ModelCapability
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Invokes the wiki-author subagent on a digest of drift events. Strategy (b)
 * (see design §5.2): exit 0 dismisses every event in the digest, non-zero exit
 * dismisses nothing.
 */
interface WikiAuthorInvoker {
    data class Result(
        val actedOnSignatures: Set<String>,
        val skippedSignatures: Set<String>,
        val errorMessage: String?,
    )
    suspend fun invoke(events: List<DriftEvent>): Result
}

/**
 * Default implementation: spawns `claude -p` with `--agents <author-only-json>`
 * on Dispatchers.IO. Uses [WikiAuthorDigestBuilder] for the prompt.
 */
class DefaultWikiAuthorInvoker(
    // Null (the default) resolves to a [DefaultProcessRunner] bound to [selection] inside [invoke],
    // so the subprocess authenticates as the WIKI role's provider (e.g. a Bedrock WIKI selection uses
    // Bedrock env vars) rather than the global active provider. Tests inject a stub directly.
    private val runner: ProcessRunner? = null,
    private val claudeCliPath: String,
    private val projectRoot: Path,
    private val mcpPort: Int = 0,
    private val modelId: String = "",
    /**
     * Invoked with the subprocess's full stream-json stdout after a run so the caller can
     * parse the trailing `result` event and attribute the wiki-author's cost. Default no-op
     * keeps the invoker usable in tests / non-UI contexts.
     */
    private val onStdout: (String) -> Unit = {},
    // Authoring a brand-new concept page (missingConcept) involves reading the
    // index, several concept pages, and multiple find_symbol/find_files
    // verification round-trips through the IDE MCP server before the final
    // Write. 300s was not enough — the process was force-killed mid-run before
    // it ever wrote the page, every cycle. Give heavy authoring room to finish.
    private val timeoutSeconds: Long = 600,
    /** Actual wiki directory; rewrites librarian `.claude/wiki/...` paths in the digest. */
    private val wikiDir: Path? = null,
    /**
     * The WIKI role's selection. Threaded into the subprocess environment so the `claude` CLI
     * authenticates as the WIKI role's provider (e.g. a Bedrock WIKI selection applies Bedrock env
     * vars) rather than the global active provider. Null keeps the historical global-provider env
     * (used by tests, which inject their own [runner] and never touch the environment).
     */
    private val selection: AgentSelection? = null,
) : WikiAuthorInvoker {

    // Resolve the process runner: an injected [runner] (tests) wins; otherwise a selection-bound
    // [DefaultProcessRunner] so the subprocess authenticates as the WIKI role's provider.
    private val effectiveRunner: ProcessRunner = runner ?: DefaultProcessRunner(selection)

    override suspend fun invoke(events: List<DriftEvent>): WikiAuthorInvoker.Result {
        if (events.isEmpty()) {
            return WikiAuthorInvoker.Result(emptySet(), emptySet(), null)
        }
        val signatures = events.map { it.signature }.toSet()
        LOG.info("wiki-author invoke: ${events.size} events; kinds=${events.groupingBy { it::class.simpleName }.eachCount()}")
        val agentsJson = try {
            WikiAgentsArg.buildAuthorOnlyJson()
        } catch (e: Throwable) {
            LOG.warn("wiki-author failed to build --agents arg: ${e.message}", e)
            return WikiAuthorInvoker.Result(emptySet(), signatures,
                "Failed to build wiki-author --agents arg: ${e.message}")
        }
        val digest = WikiAuthorDigestBuilder.build(events, wikiDir)
        val mcpConfigFile = if (mcpPort > 0) {
            try {
                val tmp = java.io.File.createTempFile("clawdea-mcp-wiki-author-", ".json")
                tmp.deleteOnExit()
                tmp.writeText(com.adobe.clawdea.mcp.buildMcpClientConfigJson(mcpPort))
                tmp
            } catch (e: Throwable) {
                LOG.warn("wiki-author failed to write MCP config: ${e.message}", e)
                null
            }
        } else null

        val command = mutableListOf(
            claudeCliPath,
            "-p",
            "--output-format", "stream-json",
            "--verbose",
            "--no-session-persistence",
            "--permission-mode", "bypassPermissions",
            "--agents", agentsJson,
            "--disallowedTools", "Bash,mcp__clawdea-intellij__propose_write,mcp__clawdea-intellij__propose_edit,mcp__clawdea-intellij__propose_multi_edit",
        )
        if (modelId.isNotBlank()) {
            command.addAll(listOf("--model", modelId))
            LOG.info("wiki-author using model: $modelId")
        } else {
            LOG.info("wiki-author no model selected; CC will use its default")
        }
        if (mcpConfigFile != null) {
            command.addAll(listOf("--mcp-config", mcpConfigFile.absolutePath))
        }
        command.addAll(listOf("--", digest))

        val result = withContext(Dispatchers.IO) {
            try {
                effectiveRunner.run(command, projectRoot, timeoutSeconds)
            } catch (e: Exception) {
                LOG.warn("wiki-author runner.run threw: ${e.javaClass.simpleName}: ${e.message}", e)
                ProcessResult(-1, "", "${e.javaClass.simpleName}: ${e.message}", timedOut = false)
            }
        }
        // Attribute the subprocess's cost (stream-json `result` event in stdout), regardless
        // of exit code — a timed-out/non-zero run can still have produced billable turns.
        if (result.stdout.isNotBlank()) {
            try {
                onStdout(result.stdout)
            } catch (e: Throwable) {
                LOG.warn("wiki-author onStdout hook threw: ${e.message}")
            }
        }
        return when {
            result.timedOut -> {
                LOG.warn("wiki-author subprocess timed out after ${timeoutSeconds}s for ${signatures.size} events; " +
                    "stdout chars=${result.stdout.length} tail: ${result.stdout.takeLast(800)}; " +
                    "stderr chars=${result.stderr.length} tail: ${result.stderr.takeLast(500)}")
                WikiAuthorInvoker.Result(emptySet(), signatures,
                    "wiki-author subprocess timed out after ${timeoutSeconds}s")
            }
            result.exitCode != 0 -> {
                LOG.warn("wiki-author subprocess exit=${result.exitCode} for ${signatures.size} events; " +
                    "stderr tail: ${result.stderr.takeLast(500)}; stdout tail: ${result.stdout.takeLast(500)}")
                WikiAuthorInvoker.Result(emptySet(), signatures,
                    "wiki-author subprocess exit code ${result.exitCode}: ${result.stderr.takeLast(500)}")
            }
            else -> {
                // Strategy-b: exit 0 means "handled". But the author can exit 0
                // while claiming success without actually creating the page (the
                // "it said it did, but it didn't" failure). For a missingConcept
                // the proof is on disk: only ack signatures whose target page now
                // exists. Unverifiable events (no wikiDir, or kinds that edit an
                // existing page) keep the exit-0 contract.
                val (acked, unverified) = events.partition { WikiAuthorVerification.isAuthored(it, wikiDir) }
                val ackedSignatures = acked.map { it.signature }.toSet()
                val unverifiedSignatures = unverified.map { it.signature }.toSet()
                if (unverifiedSignatures.isNotEmpty()) {
                    LOG.warn("wiki-author exited 0 but ${unverifiedSignatures.size} event(s) left no file on disk; " +
                        "not dismissing so they retry: $unverifiedSignatures")
                }
                LOG.info("wiki-author dismissed ${ackedSignatures.size} events (strategy-b); " +
                    "withheld ${unverifiedSignatures.size} unverified")
                WikiAuthorInvoker.Result(ackedSignatures, unverifiedSignatures, null)
            }
        }
    }

    interface ProcessRunner {
        fun run(command: List<String>, projectRoot: Path, timeoutSeconds: Long): ProcessResult
    }

    data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    /**
     * Real process runner. When constructed with a [selection], the subprocess environment is
     * populated for that WIKI selection's provider (so a Bedrock/Vertex WIKI selection authenticates
     * correctly); null applies the global active provider (legacy behavior).
     */
    class DefaultProcessRunner(private val selection: AgentSelection? = null) : ProcessRunner {
        override fun run(command: List<String>, projectRoot: Path, timeoutSeconds: Long): ProcessResult {
            val pb = ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(false)
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            val merged = mutableMapOf<String, String>()
            com.adobe.clawdea.cli.CliEnvironment.applyTo(merged)
            for ((k, v) in System.getenv()) merged.putIfAbsent(k, v)
            val auth = com.adobe.clawdea.auth.AuthManager.getInstance()
            if (selection != null) auth.applyToEnvironment(merged, selection) else auth.applyToEnvironment(merged)
            val env = pb.environment()
            env.clear()
            env.putAll(merged)
            val process = pb.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val out = drain(process.inputStream.bufferedReader(StandardCharsets.UTF_8), stdout)
            val err = drain(process.errorStream.bufferedReader(StandardCharsets.UTF_8), stderr)
            return if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                out.join(500); err.join(500)
                ProcessResult(-1, stdout.toString(), stderr.toString(), timedOut = true)
            } else {
                out.join(500); err.join(500)
                ProcessResult(process.exitValue(), stdout.toString(), stderr.toString(), timedOut = false)
            }
        }

        private fun drain(reader: BufferedReader, output: StringBuilder): Thread =
            Thread { reader.useLines { lines -> for (line in lines) output.appendLine(line) } }
                .apply { isDaemon = true; start() }
    }

    companion object {
        private val LOG = Logger.getInstance(DefaultWikiAuthorInvoker::class.java)
    }
}

/**
 * Shared post-run verification for BOTH wiki-author paths (Claude CLI and the agentic
 * openai-compatible path). Closes the "model claimed success but wrote nothing" gap: a clean run is
 * only trusted for a `missingConcept` event if its target page now exists on disk.
 */
object WikiAuthorVerification {
    /**
     * Whether [event] can be treated as authored after a clean (exit-0 / no-error) run. A
     * `missingConcept` suggestion is only authored if its target page now exists on disk. Other
     * events can't be cheaply verified by existence (they edit an already-present page), so they
     * keep the clean-completion contract.
     */
    fun isAuthored(event: DriftEvent, wikiDir: Path?): Boolean {
        if (event !is DriftEvent.WikiSuggestion) return true
        if (event.kind != SuggestionKind.missingConcept) return true
        val dir = wikiDir ?: return true
        val target = DriftEvent.WikiSuggestion.primaryTarget(event.targetFiles)
        if (target.isBlank()) return true
        val rel = target.removePrefix(".claude/wiki/")
        return java.nio.file.Files.exists(dir.resolve(rel))
    }
}

/**
 * Runs one wiki-author session against a digest prompt, driving the model's tool calls until the
 * turn terminates. Returns success when the session completed without a terminal error, failure
 * (with the error text) otherwise. The wiki content is authored as a SIDE EFFECT of the tools the
 * session executes (Edit/Write/apply_patch through the MCP + host catalog), so the invoker treats a
 * clean completion as "acted on all events".
 */
fun interface AgenticWikiSession {
    suspend fun run(digest: String): Result<Unit>
}

/**
 * Non-Claude wiki-author path (capability-tiered fallback per design §5.2). Builds the SAME digest
 * as the Claude path via [WikiAuthorDigestBuilder] and drives it through an agentic tool loop (via
 * [AgenticWikiSession]) so the model can call find_symbol/find_files/read + Edit/Write to author
 * pages. There is NO `--agents` subagent injection — that is a Claude-CLI-only mechanism.
 *
 * Capability guard: a WIKI selection whose model is not [ModelCapability.AGENTIC] cannot execute
 * tools, so it can never edit files. Rather than run a tool-less author (which would "succeed"
 * without writing anything), the invoker refuses and reports a clear, actionable error.
 */
class AgenticWikiAuthorInvoker(
    private val selection: AgentSelection,
    private val wikiDir: Path?,
    private val capability: ModelCapability,
    private val session: AgenticWikiSession,
) : WikiAuthorInvoker {

    override suspend fun invoke(events: List<DriftEvent>): WikiAuthorInvoker.Result {
        if (events.isEmpty()) {
            return WikiAuthorInvoker.Result(emptySet(), emptySet(), null)
        }
        val signatures = events.map { it.signature }.toSet()

        // Capability guard: wiki authoring REQUIRES tools. A completion-only (or unknown) model can
        // only emit text and would leave the wiki untouched while appearing to "handle" the events.
        if (capability != ModelCapability.AGENTIC) {
            val msg = "WIKI provider model '${selection.modelId}' is not tool-capable; " +
                "assign an agentic model in Settings > Roles"
            LOG.warn("wiki-author (agentic) refused: $msg (capability=$capability)")
            return WikiAuthorInvoker.Result(emptySet(), signatures, msg)
        }

        val digest = WikiAuthorDigestBuilder.build(events, wikiDir)
        LOG.info("wiki-author (agentic) invoke: ${events.size} events via ${selection.providerId}/${selection.modelId}")

        val result = try {
            session.run(digest)
        } catch (e: Throwable) {
            LOG.warn("wiki-author (agentic) session threw: ${e.message}", e)
            Result.failure(e)
        }

        return if (result.isSuccess) {
            // Same on-disk proof the Claude path uses: a missingConcept whose target page was NOT
            // written is WITHHELD (reported skipped → retries), guarding the "model finished without
            // calling Write/apply_patch" failure that would otherwise silently dismiss the event.
            val (acked, unverified) = events.partition { WikiAuthorVerification.isAuthored(it, wikiDir) }
            val ackedSignatures = acked.map { it.signature }.toSet()
            val unverifiedSignatures = unverified.map { it.signature }.toSet()
            if (unverifiedSignatures.isNotEmpty()) {
                LOG.warn("wiki-author (agentic) completed but ${unverifiedSignatures.size} event(s) " +
                    "left no file on disk; not dismissing so they retry: $unverifiedSignatures")
            }
            LOG.info("wiki-author (agentic) dismissed ${ackedSignatures.size} events; " +
                "withheld ${unverifiedSignatures.size} unverified")
            WikiAuthorInvoker.Result(ackedSignatures, unverifiedSignatures, null)
        } else {
            val err = result.exceptionOrNull()?.message ?: "unknown error"
            LOG.warn("wiki-author (agentic) failed for ${signatures.size} events: $err")
            WikiAuthorInvoker.Result(emptySet(), signatures, "wiki-author (agentic) failed: $err")
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(AgenticWikiAuthorInvoker::class.java)
    }
}

/**
 * Production [AgenticWikiSession] backed by the same [AgentLoopController] the chat uses. Feeds the
 * digest as the user turn (optionally after a system prompt), drains the loop to its terminal
 * [CliEvent.Result], and maps error/non-error to a [Result]. Kept free of IntelliJ/scope concerns so
 * it is unit-testable with injected [AgentClient]/[AgentToolExecutor] fakes.
 */
class LoopBackedWikiSession(
    private val client: AgentClient,
    private val executor: AgentToolExecutor,
    private val tools: List<OpenAiToolDefinition>,
    private val modelId: String,
    private val systemPrompt: String?,
    private val streaming: Boolean,
    private val maxToolRounds: Int = 30,
    private val maxElapsedMs: Long = 600_000,
    private val maxContextChars: Int = 1_000_000,
) : AgenticWikiSession {

    override suspend fun run(digest: String): Result<Unit> {
        val state = ConversationState()
        if (!systemPrompt.isNullOrBlank()) {
            state.messages.add(AgentMessage(role = "system", content = systemPrompt))
        }
        val loop = AgentLoopController(
            client = client,
            executor = executor,
            state = state,
            maxToolRounds = maxToolRounds,
            maxElapsedMs = maxElapsedMs,
            maxContextChars = maxContextChars,
            modelId = modelId,
            tools = tools,
            stream = streaming,
        )
        var terminalError: String? = null
        val turn = loop.runTurn(digest, appendUserMessage = true) { event ->
            if (event is CliEvent.Result && event.isError) {
                terminalError = event.text
            }
        }
        // A failed stream returns streamFailed=true WITHOUT a terminal Result; treat it as an error
        // too (the wiki path has no retry orchestration of its own — one shot).
        return when {
            terminalError != null -> Result.failure(RuntimeException(terminalError))
            turn.streamFailed -> Result.failure(RuntimeException(turn.finalText.ifBlank { "request failed" }))
            turn.isError -> Result.failure(RuntimeException(turn.finalText.ifBlank { "wiki-author turn error" }))
            else -> Result.success(Unit)
        }
    }
}

/**
 * Codex (app-server) wiki-author path. Wiring the codex app-server for headless, unattended
 * tool-loop authoring is out of scope for this task: return a clear "not supported" result so the
 * events are retried rather than silently dismissed, and surface the reason.
 */
object CodexUnsupportedWikiAuthorInvoker : WikiAuthorInvoker {
    override suspend fun invoke(events: List<DriftEvent>): WikiAuthorInvoker.Result {
        if (events.isEmpty()) return WikiAuthorInvoker.Result(emptySet(), emptySet(), null)
        val signatures = events.map { it.signature }.toSet()
        return WikiAuthorInvoker.Result(
            emptySet(),
            signatures,
            "Codex wiki-author is not supported; assign a Claude or OpenAI-compatible agentic model " +
                "to the WIKI role in Settings > Roles",
        )
    }
}
