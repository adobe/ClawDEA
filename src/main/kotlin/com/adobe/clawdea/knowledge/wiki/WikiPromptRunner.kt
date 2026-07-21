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
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.knowledge.drift.AgenticWikiSession
import com.adobe.clawdea.knowledge.drift.DefaultWikiAuthorInvoker
import com.adobe.clawdea.provider.openai.catalog.ModelCapability
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Runs an arbitrary wiki-authoring PROMPT (not a drift-event digest) as the WIKI role, so
 * `/seed-wiki` bootstraps under the model/provider chosen in Settings → Roles rather than the
 * chat tab's model. Parallel to [com.adobe.clawdea.knowledge.drift.WikiAuthorInvoker] but keyed on
 * a raw prompt; it reuses that path's primitives (`DefaultProcessRunner`, `AgenticWikiSession`) and
 * deliberately leaves the event-based author path untouched.
 */
interface WikiPromptRunner {
    data class Result(val ok: Boolean, val errorMessage: String?, val stdout: String)
    suspend fun run(prompt: String): Result
}

/**
 * Claude-family WIKI role: `claude -p --output-format stream-json --permission-mode bypassPermissions
 * --model <wiki> --mcp-config <file> -- <prompt>`. No `--agents` (seed is a plain bootstrap prompt,
 * not a subagent dispatch). Under bypassPermissions the model uses the built-in Write/Edit tools with
 * no review dialog, which is why the seed prompt must instruct Write, not propose_write.
 */
class ClaudeWikiPromptRunner(
    private val runner: DefaultWikiAuthorInvoker.ProcessRunner,
    private val claudeCliPath: String,
    private val projectRoot: Path,
    private val mcpPort: Int = 0,
    private val modelId: String = "",
    private val timeoutSeconds: Long = 600,
    private val onStdout: (String) -> Unit = {},
) : WikiPromptRunner {

    override suspend fun run(prompt: String): WikiPromptRunner.Result {
        val mcpConfigFile = if (mcpPort > 0) {
            try {
                val tmp = java.io.File.createTempFile("clawdea-mcp-seed-wiki-", ".json")
                tmp.deleteOnExit()
                tmp.writeText(com.adobe.clawdea.mcp.buildMcpClientConfigJson(mcpPort))
                tmp
            } catch (e: Throwable) {
                LOG.warn("seed-wiki failed to write MCP config: ${e.message}", e); null
            }
        } else null

        val command = mutableListOf(
            claudeCliPath,
            "-p",
            "--output-format", "stream-json",
            "--verbose",
            "--no-session-persistence",
            "--permission-mode", "bypassPermissions",
        )
        if (modelId.isNotBlank()) command.addAll(listOf("--model", modelId))
        if (mcpConfigFile != null) command.addAll(listOf("--mcp-config", mcpConfigFile.absolutePath))
        command.addAll(listOf("--", prompt))

        val result = withContext(Dispatchers.IO) {
            try {
                runner.run(command, projectRoot, timeoutSeconds)
            } catch (e: Exception) {
                LOG.warn("seed-wiki runner threw: ${e.message}", e)
                DefaultWikiAuthorInvoker.ProcessResult(-1, "", "${e.javaClass.simpleName}: ${e.message}", timedOut = false)
            }
        }
        if (result.stdout.isNotBlank()) {
            try { onStdout(result.stdout) } catch (e: Throwable) { LOG.warn("seed-wiki onStdout threw: ${e.message}") }
        }
        return when {
            result.timedOut -> WikiPromptRunner.Result(false, "seed-wiki timed out after ${timeoutSeconds}s", result.stdout)
            result.exitCode != 0 -> WikiPromptRunner.Result(false,
                "seed-wiki subprocess exit ${result.exitCode}: ${result.stderr.takeLast(500)}", result.stdout)
            else -> WikiPromptRunner.Result(true, null, result.stdout)
        }
    }

    private companion object { val LOG = Logger.getInstance(ClaudeWikiPromptRunner::class.java) }
}

/**
 * OpenAI-compatible WIKI role: drive the prompt through the shared agentic loop ([AgenticWikiSession]).
 * Refuses a non-[ModelCapability.AGENTIC] model (a completion-only model can't call the tools that
 * write files) with a clear, actionable message — mirroring [AgenticWikiAuthorInvoker].
 */
class AgenticWikiPromptRunner(
    private val session: AgenticWikiSession,
    private val capability: ModelCapability,
    private val modelLabel: String,
) : WikiPromptRunner {

    override suspend fun run(prompt: String): WikiPromptRunner.Result {
        if (capability != ModelCapability.AGENTIC) {
            return WikiPromptRunner.Result(false,
                "WIKI provider model '$modelLabel' is not tool-capable; assign an agentic model in Settings > Roles", "")
        }
        val result = try {
            session.run(prompt)
        } catch (e: Throwable) {
            LOG.warn("seed-wiki (agentic) threw: ${e.message}", e)
            Result.failure(e)
        }
        return if (result.isSuccess) WikiPromptRunner.Result(true, null, "")
        else WikiPromptRunner.Result(false, "seed-wiki (agentic) failed: ${result.exceptionOrNull()?.message ?: "unknown"}", "")
    }

    private companion object { val LOG = Logger.getInstance(AgenticWikiPromptRunner::class.java) }
}

/** Codex WIKI role: unattended tool-loop authoring is not wired for codex; refuse with a clear message. */
object CodexUnsupportedWikiPromptRunner : WikiPromptRunner {
    override suspend fun run(prompt: String): WikiPromptRunner.Result =
        WikiPromptRunner.Result(false,
            "Codex seed-wiki is not supported; assign a Claude or OpenAI-compatible agentic model to the WIKI role in Settings > Roles", "")
}
