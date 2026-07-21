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

import com.adobe.clawdea.auth.AuthManager
import com.adobe.clawdea.cli.CliEnvironment
import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.cli.CliEventParser
import com.adobe.clawdea.provider.AgentSelection
import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs ONE wiki-librarian question through a headless `claude -p` subprocess authenticated as the
 * WIKI role's (Claude-family) provider, and returns the model's final assistant text. This is the
 * cross-backend path: a Codex or openai-compatible CHAT cannot spawn the `--agents` wiki-librarian
 * subagent, so `ask_wiki_librarian` shells out to `claude` for a Claude WIKI role. Read-only:
 * permissions are bypassed (no UI is reachable here), so read-only is enforced by a positive
 * `--allowedTools` allowlist derived from [LIBRARIAN_TOOL_NAMES] (a denylist would be unsafe under
 * bypass). Symmetric to [com.adobe.clawdea.knowledge.drift.DefaultWikiAuthorInvoker] but returns text.
 */
class ClaudeSubprocessLibrarian(
    private val claudeCliPath: String,
    private val projectRoot: Path,
    private val mcpPort: Int,
    private val selection: AgentSelection,
    private val runner: Runner = DefaultRunner,
    private val timeoutSeconds: Long = 300,
) {
    fun ask(question: String): LibrarianAnswer {
        val systemPrompt = try {
            WikiAgentsArg.librarianPromptBody()
        } catch (e: Throwable) {
            LOG.warn("librarian prompt body missing; running without persona: ${e.message}")
            null
        }
        val mcpConfig = if (mcpPort > 0) {
            try {
                val tmp = java.io.File.createTempFile("clawdea-mcp-librarian-", ".json")
                tmp.deleteOnExit()
                tmp.writeText(com.adobe.clawdea.mcp.buildMcpClientConfigJson(mcpPort))
                tmp
            } catch (e: Throwable) {
                LOG.warn("librarian MCP config write failed: ${e.message}"); null
            }
        } else null

        // Build allowlist from LIBRARIAN_TOOL_NAMES. Under bypassPermissions, a denylist is unsafe
        // (any non-denied tool auto-runs, exposing debug_evaluate, NotebookEdit, etc.). An allowlist
        // enforces read-only at the CLI layer, mirroring the agentic path's LIBRARIAN_TOOL_NAMES guard.
        val allowedTools = LIBRARIAN_TOOL_NAMES.joinToString(",") {
            if (it == "Read") it else "mcp__clawdea-intellij__$it"
        }

        val command = mutableListOf(
            claudeCliPath,
            "-p",
            "--output-format", "stream-json",
            "--verbose",
            "--no-session-persistence",
            "--permission-mode", "bypassPermissions",
            "--allowedTools", allowedTools,
        )
        if (selection.modelId.isNotBlank()) command.addAll(listOf("--model", selection.modelId))
        if (systemPrompt != null) command.addAll(listOf("--append-system-prompt", systemPrompt))
        if (mcpConfig != null) command.addAll(listOf("--mcp-config", mcpConfig.absolutePath))
        command.addAll(listOf("--", question))

        val result = try {
            runner.run(command, projectRoot, selection, timeoutSeconds)
        } catch (e: Exception) {
            LOG.warn("librarian subprocess threw: ${e.message}", e)
            return LibrarianAnswer("librarian subprocess failed: ${e.message}", isError = true)
        }

        if (result.timedOut) {
            return LibrarianAnswer("wiki-librarian timed out after ${timeoutSeconds}s", isError = true)
        }
        val parsed = extractFinalText(result.stdout)
        return when {
            parsed.errored && parsed.text.isBlank() -> LibrarianAnswer(
                "wiki-librarian returned no answer (exit ${result.exitCode}): " +
                    result.stderr.takeLast(400).ifBlank { "no output" },
                isError = true,
            )
            parsed.errored -> LibrarianAnswer(parsed.text, isError = true)
            parsed.text.isNotBlank() -> LibrarianAnswer(parsed.text, isError = false)
            else -> LibrarianAnswer(
                "wiki-librarian returned no answer (exit ${result.exitCode}): " +
                    result.stderr.takeLast(400).ifBlank { "no output" },
                isError = true,
            )
        }
    }

    /** Parse stream-json stdout with CliEventParser; accumulate assistant text; error on Result.isError. */
    private fun extractFinalText(stdout: String): ParsedOutput {
        val parser = CliEventParser()
        val sb = StringBuilder()
        var errored = false
        for (line in stdout.lineSequence()) {
            if (line.isBlank()) continue
            when (val ev = parser.parse(line)) {
                is CliEvent.TextDelta -> sb.append(ev.text)
                is CliEvent.AssistantMessage -> if (ev.text.isNotBlank()) { sb.setLength(0); sb.append(ev.text) }
                is CliEvent.Result -> if (ev.isError) errored = true
                else -> {}
            }
        }
        // Prefer the terminal AssistantMessage (full text) when present; TextDelta accumulation is the
        // fallback. On error, still return whatever text we have so the caller can surface it.
        return ParsedOutput(sb.toString().trim(), errored)
    }

    private data class ParsedOutput(val text: String, val errored: Boolean)

    interface Runner {
        fun run(command: List<String>, projectRoot: Path, selection: AgentSelection, timeoutSeconds: Long): RunResult
    }

    data class RunResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    object DefaultRunner : Runner {
        override fun run(command: List<String>, projectRoot: Path, selection: AgentSelection, timeoutSeconds: Long): RunResult {
            // Build env here (not in ask()) so production auth failures surface instead of being swallowed.
            // Test runners never call CliEnvironment/AuthManager (they're injected), so tests remain headless.
            val env = mutableMapOf<String, String>()
            CliEnvironment.applyTo(env)
            for ((k, v) in System.getenv()) env.putIfAbsent(k, v)
            AuthManager.getInstance().applyToEnvironment(env, selection)

            val pb = ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(false)
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            pb.environment().apply { clear(); putAll(env) }
            val process = pb.start()
            val out = StringBuilder(); val err = StringBuilder()
            val ot = drain(process.inputStream.bufferedReader(StandardCharsets.UTF_8), out)
            val et = drain(process.errorStream.bufferedReader(StandardCharsets.UTF_8), err)
            return if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly(); ot.join(500); et.join(500)
                RunResult(-1, out.toString(), err.toString(), timedOut = true)
            } else {
                ot.join(500); et.join(500)
                RunResult(process.exitValue(), out.toString(), err.toString(), timedOut = false)
            }
        }

        private fun drain(reader: java.io.BufferedReader, sink: StringBuilder): Thread =
            Thread { reader.useLines { for (l in it) sink.appendLine(l) } }.apply { isDaemon = true; start() }
    }

    private companion object { val LOG = Logger.getInstance(ClaudeSubprocessLibrarian::class.java) }
}
