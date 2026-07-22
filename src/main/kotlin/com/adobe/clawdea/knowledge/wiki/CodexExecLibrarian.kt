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
import com.adobe.clawdea.provider.AgentSelection
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs ONE wiki-librarian question through a headless `codex exec --json` subprocess authenticated as
 * the WIKI role's (Codex-family) provider, and returns the model's final assistant text. This is the
 * Codex analogue of [ClaudeSubprocessLibrarian]: a Codex chat cannot spawn the `--agents`
 * wiki-librarian subagent, so `ask_wiki_librarian` shells out to `codex exec`.
 *
 * ### Read-only by construction — and why it deliberately forgoes ClawDEA's MCP tools
 * The spike (`docs/superpowers/specs/2026-07-14-codex-interface-findings.md`, "Approval mechanism" +
 * Phase 2 §3) established that with `codex exec`, MCP `tools/call` only executes under
 * `-s danger-full-access`; any real sandbox (`read-only`/`workspace-write`) blocks the loopback MCP
 * socket on macOS Seatbelt. So exposing ClawDEA's MCP server would force `danger-full-access`, which
 * leaves codex's own shell UNGATED — the opposite of a read-only librarian.
 *
 * Instead this runs under `-s read-only -c approval_policy="never"` with NO MCP server: the librarian
 * reads the on-disk wiki (`docs/llm-wiki/…`) and greps the tree with codex's built-in shell. Read
 * commands need no sandbox escalation, so they run; a write would need escalation and, under
 * `approval_policy="never"`, simply fails back to the model (no prompt, no hang, no write). Net: a
 * genuinely read-only Q&A with no `danger-full-access`. The tradeoff vs. the Claude/agentic paths is
 * no `record_wiki_suggestion` gap-logging and no IntelliJ index tools — acceptable for read-only Q&A.
 *
 * `--ephemeral` avoids persisting a rollout session for this throwaway turn; `--skip-git-repo-check`
 * lets it run regardless of the project's VCS state. Symmetric to [ClaudeSubprocessLibrarian] but over
 * the `codex exec --json` stream (`thread.started` / `item.completed` / `turn.completed`), which is
 * distinct from the app-server notification stream that [com.adobe.clawdea.cli.CodexAppServerParser]
 * handles.
 */
class CodexExecLibrarian(
    private val codexCliPath: String,
    private val projectRoot: Path,
    private val selection: AgentSelection,
    private val runner: Runner = DefaultRunner,
    private val timeoutSeconds: Long = 300,
) {
    fun ask(question: String): LibrarianAnswer {
        val persona = try {
            WikiAgentsArg.librarianPromptBody()
        } catch (e: Throwable) {
            LOG.warn("librarian prompt body missing; running without persona: ${e.message}")
            null
        }
        // codex exec has no `--append-system-prompt`; fold the persona + question into the one prompt.
        // The persona references MCP tool names it cannot use here, so a short preamble redirects it to
        // reading the on-disk wiki with the shell.
        val prompt = buildPrompt(persona, question)

        val command = mutableListOf(codexCliPath)
        // `-m/--model` is a TOP-LEVEL flag (must precede `exec`); see spike Phase 2 §4.
        if (selection.modelId.isNotBlank() && selection.modelId != "default") {
            command.addAll(listOf("-m", selection.modelId))
        }
        command.addAll(
            listOf(
                "exec",
                "--json",
                "--skip-git-repo-check",
                "--ephemeral",
                "-s", "read-only",
                "-c", "approval_policy=\"never\"",
                prompt,
            ),
        )

        val result = try {
            runner.run(command, projectRoot, selection, timeoutSeconds)
        } catch (e: Exception) {
            LOG.warn("codex librarian subprocess threw: ${e.message}", e)
            return LibrarianAnswer("codex librarian subprocess failed: ${e.message}", isError = true)
        }

        if (result.timedOut) {
            return LibrarianAnswer("wiki-librarian timed out after ${timeoutSeconds}s", isError = true)
        }
        val parsed = extractFinalText(result.stdout)
        return when {
            parsed.errored && parsed.text.isBlank() -> LibrarianAnswer(
                "wiki-librarian returned no answer (exit ${result.exitCode}): " +
                    (parsed.errorMessage ?: result.stderr.takeLast(400).ifBlank { "no output" }),
                isError = true,
            )
            parsed.errored -> LibrarianAnswer(parsed.text.ifBlank { parsed.errorMessage ?: "" }, isError = true)
            parsed.text.isNotBlank() -> LibrarianAnswer(parsed.text, isError = false)
            else -> LibrarianAnswer(
                "wiki-librarian returned no answer (exit ${result.exitCode}): " +
                    result.stderr.takeLast(400).ifBlank { "no output" },
                isError = true,
            )
        }
    }

    /**
     * Parse `codex exec --json` NDJSON stdout: accumulate `agent_message` item text (multiple items
     * per turn — a preamble item plus the final answer — so the LAST non-blank one wins), and flag an
     * error on `turn.failed` / top-level `error`.
     */
    private fun extractFinalText(stdout: String): ParsedOutput {
        var text = ""
        var errored = false
        var errorMessage: String? = null
        for (line in stdout.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) continue
            val obj = try {
                JsonParser.parseString(trimmed).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: Exception) {
                null
            } ?: continue
            when (obj.str("type")) {
                "item.completed" -> {
                    val item = obj.getAsJsonObjectOrNull("item") ?: continue
                    if (item.str("type") == "agent_message") {
                        val t = item.str("text").orEmpty()
                        if (t.isNotBlank()) text = t
                    }
                }
                "turn.failed" -> {
                    errored = true
                    errorMessage = obj.getAsJsonObjectOrNull("error")?.str("message") ?: errorMessage
                }
                "error" -> {
                    errored = true
                    errorMessage = obj.str("message") ?: errorMessage
                }
            }
        }
        return ParsedOutput(text.trim(), errored, errorMessage)
    }

    private data class ParsedOutput(val text: String, val errored: Boolean, val errorMessage: String?)

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
                // codex exec reads stdin as an extra `<stdin>` block and blocks on an open pipe until
                // EOF (spike "stdin caveat") — redirect from the null device so the one-shot proceeds.
                // Portable across OSes (`NUL` on Windows, `/dev/null` elsewhere).
                .redirectInput(com.adobe.clawdea.util.NullDevice.inputRedirect())
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

    companion object {
        private val LOG = Logger.getInstance(CodexExecLibrarian::class.java)

        /**
         * Folds the librarian persona and the user's question into codex exec's single positional
         * prompt, with a preamble that redirects the persona's MCP-tool workflow to the on-disk wiki
         * (codex has no ClawDEA MCP tools here — see the class doc).
         */
        internal fun buildPrompt(persona: String?, question: String): String {
            val preamble =
                "You are running headless with read-only filesystem access and NO MCP tools. " +
                    "Ignore any instruction below to call tools like read_wiki_page, find_symbol, or " +
                    "record_wiki_suggestion — those are unavailable. Instead, read the project wiki " +
                    "markdown directly from the docs/llm-wiki/ directory (start with its index, then the " +
                    "relevant concept pages) and grep the source tree with shell commands to verify " +
                    "claims. Your final message must be the synthesised answer.\n\n"
            val personaBlock = if (!persona.isNullOrBlank()) persona.trim() + "\n\n" else ""
            return preamble + personaBlock + "Question: " + question
        }

        private fun JsonObject.str(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
            get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    }
}
