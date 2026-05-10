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
package com.adobe.clawdea.cli

import com.adobe.clawdea.auth.AuthManager
import com.adobe.clawdea.knowledge.primer.PrimerService
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.skills.SkillInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedDeque

class CliStartException(override val message: String) : RuntimeException(message)

class CliProcess(
    private val workingDirectory: String,
    private val mcpPort: Int = 0,
    private val project: Project? = null,
) {

    private val log = Logger.getInstance(CliProcess::class.java)

    private var process: Process? = null
    private var stdoutReader: BufferedReader? = null
    private var stdinWriter: BufferedWriter? = null
    private var stderrThread: Thread? = null
    private var mcpConfigFile: java.io.File? = null
    private val recentStderr = ConcurrentLinkedDeque<String>()

    val isAlive: Boolean
        get() = process?.isAlive == true

    fun start(resumeSessionId: String? = null, skills: List<SkillInfo> = emptyList()) {
        if (isAlive) return

        recentStderr.clear()
        val settings = ClawDEASettings.getInstance().state
        val cliPath = resolveCliPath(settings.cliPath)

        val env = mutableMapOf<String, String>()
        CliEnvironment.applyTo(env)
        for ((k, v) in System.getenv()) env.putIfAbsent(k, v)
            AuthManager.getInstance().applyToEnvironment(env)

        val authCheck = com.adobe.clawdea.auth.AuthManager.getInstance().preflight()
        preflightChecks(cliPath, env, authCheck)

        // We deliberately do NOT pass --include-hook-events.
        //
        // ClawDEA *is* the harness, with first-class UX for the lifecycle
        // moments hooks would otherwise expose (edit review via propose_edit,
        // permission approval via the MCP request_permission tool, session
        // start/end via ChatPanel). User-configured Claude Code hooks would
        // either duplicate or conflict with that UX, and CliEventParser does
        // not model PreToolUse/PostToolUse/Stop/etc. event shapes.
        //
        // Regression: CliProcessHookEventsOmissionTest. Drift watcher entry
        // (#94) ensures we revisit this if upstream changes hook semantics.
        val command = mutableListOf(
            cliPath,
            "-p",
            "--output-format", "stream-json",
            "--input-format", "stream-json",
            "--verbose",
            "--include-partial-messages",
            // Move per-machine sections (cwd, env info, memory paths, git status)
            // into the first user message so cross-user prompt-cache reuse is
            // possible. Only takes effect with the default system prompt; ignored
            // when --system-prompt overrides it.
            "--exclude-dynamic-system-prompt-sections",
        )
        command.addAll(buildSettingSourceArgs())

        if (mcpPort > 0) {
            command.addAll(buildPermissionArgs(settings.toolApprovalMode))
            command.addAll(buildPermissionSettingsArgs(settings.toolApprovalMode))
            command.addAll(listOf("--permission-prompt-tool", "mcp__clawdea-intellij__request_permission"))

            val mcpJson = com.adobe.clawdea.mcp.buildMcpClientConfigJson(mcpPort)
            val tmpFile = java.io.File.createTempFile("clawdea-mcp-", ".json")
            tmpFile.deleteOnExit()
            tmpFile.writeText(mcpJson)
            mcpConfigFile = tmpFile
            log.info("Wrote MCP config to ${tmpFile.absolutePath}")

            command.addAll(listOf("--mcp-config", tmpFile.absolutePath))

            val disallowed = buildDisallowedTools(mcpAvailable = true)
            if (disallowed != null) {
                command.addAll(listOf("--disallowedTools", disallowed))
            }

            val systemPrompt = buildString {
                append(MCP_SYSTEM_PROMPT)
                append("\n\n")
                append(EDIT_REVIEW_PROMPT)
                if (settings.preloadSkillCatalog && skills.isNotEmpty()) {
                    append("\n\n")
                    append(buildSkillCatalogPrompt(skills))
                }
                if (settings.enableKnowledgeLayer && project != null) {
                    val primer = try {
                        PrimerService.getInstance(project).refreshAndGet()
                    } catch (e: Exception) {
                        log.warn("PrimerService threw during CLI start; continuing without primer", e)
                        ""
                    }
                    if (primer.isNotBlank()) {
                        append("\n\n")
                        append(primer)
                    }
                }
            }
            command.addAll(listOf("--append-system-prompt", systemPrompt))
        }
        // No-MCP path intentionally adds no permission flags. Without MCP we have
        // no approval UI; the CLI falls back to its default mode and any flagged
        // tool call will silently fail in stream-json. This is a degraded mode
        // triggered only when the local MCP HTTP server fails to bind.

        val effectiveProvider = com.adobe.clawdea.auth.AuthManager.getInstance().effectiveProviderId()
        val selectedModel = ClawDEASettings.getInstance().getCliModelId(workingDirectory, effectiveProvider)
        command.addAll(buildModelArg(selectedModel))

        val selectedEffort = ClawDEASettings.getInstance().getSelectedEffort(workingDirectory)
        command.addAll(buildEffortArg(selectedEffort))

        if (!resumeSessionId.isNullOrBlank()) {
            command.addAll(listOf("--resume", resumeSessionId))
        }

        if (settings.cliExtraArgs.isNotBlank()) {
            command.addAll(sanitizeCliExtraArgs(tokenizeArgs(settings.cliExtraArgs)))
        }

        log.info("Starting CLI process: ${command.joinToString(" ")}")

        val pb = ProcessBuilder(command)
            .directory(java.io.File(workingDirectory))
            .redirectErrorStream(false)

        val processEnv = pb.environment()
        processEnv.clear()
        processEnv.putAll(env)

        process = pb.start()
        stdoutReader = BufferedReader(InputStreamReader(process!!.inputStream, StandardCharsets.UTF_8))
        stdinWriter = BufferedWriter(OutputStreamWriter(process!!.outputStream, StandardCharsets.UTF_8))

        // Read stderr in a background thread for diagnostics
        stderrThread = Thread({
            try {
                BufferedReader(InputStreamReader(process!!.errorStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        rememberStderr(line)
                        log.info("CLI stderr: $line")
                    }
                }
            } catch (_: Exception) {}
        }, "ClawDEA-CLI-stderr").apply {
            isDaemon = true
            start()
        }

        log.info("CLI process started (PID: ${process!!.pid()})")
    }

    fun recentStderrLines(): List<String> = recentStderr.toList()

    internal fun resolveCliPath(configured: String): String = resolveClaudeCliPath(configured)

    fun readLine(): String? {
        return try {
            stdoutReader?.readLine()
        } catch (e: Exception) {
            log.warn("Error reading from CLI stdout", e)
            null
        }
    }

    fun writeLine(line: String) {
        try {
            stdinWriter?.write(line)
            stdinWriter?.newLine()
            stdinWriter?.flush()
        } catch (e: Exception) {
            log.warn("Error writing to CLI stdin", e)
        }
    }

    fun sendInterrupt() {
        val proc = process ?: return
        try {
            val pid = proc.pid()
            Runtime.getRuntime().exec(arrayOf("kill", "-INT", pid.toString())).waitFor()
        } catch (e: Exception) {
            log.warn("Error sending SIGINT to CLI (pid=${proc.pid()})", e)
        }
    }

    fun stop() {
        val proc = process ?: return
        log.info("Stopping CLI process (PID: ${proc.pid()})")

        try {
            stdinWriter?.close()

            if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.info("CLI process did not exit in 5s, destroying")
                proc.destroy()

                if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("CLI process did not respond to destroy, force killing")
                    proc.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            log.warn("Error stopping CLI process", e)
            proc.destroyForcibly()
        } finally {
            try { stdoutReader?.close() } catch (_: Exception) {}
            try { mcpConfigFile?.delete() } catch (_: Exception) {}
            stderrThread?.let { t ->
                t.interrupt()
                try { t.join(1000) } catch (_: Exception) {}
            }
            process = null
            stdoutReader = null
            stdinWriter = null
            stderrThread = null
            mcpConfigFile = null
        }
    }

    private fun rememberStderr(line: String) {
        recentStderr.addLast(line)
        while (recentStderr.size > MAX_RECENT_STDERR_LINES) {
            recentStderr.pollFirst()
        }
    }

    companion object {
        private const val MAX_RECENT_STDERR_LINES = 40

        fun preflightChecks(
            cliPath: String,
            processEnv: Map<String, String>,
            authValidation: com.adobe.clawdea.auth.AuthValidation,
        ) {
            // Check 1: Binary exists and is executable
            val file = java.io.File(cliPath)
            if (cliPath == "claude" || !file.isFile || !file.canExecute()) {
                throw CliStartException(
                    "Claude CLI not found. Install it with: npm install -g @anthropic-ai/claude-code" +
                        " — or set the path in Settings > Tools > ClawDEA."
                )
            }

            // Check 2: CLI responds to --version
            try {
                val versionProc = ProcessBuilder(cliPath, "--version")
                    .redirectErrorStream(true)
                    .start()
                val exited = versionProc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!exited) {
                    versionProc.destroyForcibly()
                    throw CliStartException(
                        "Claude CLI found at $cliPath but not responding. Try reinstalling:" +
                            " npm install -g @anthropic-ai/claude-code"
                    )
                }
                val output = versionProc.inputStream.bufferedReader().readText().trim()
                if (versionProc.exitValue() != 0 || output.isBlank()) {
                    throw CliStartException(
                        "Claude CLI found at $cliPath but not responding. Try reinstalling:" +
                            " npm install -g @anthropic-ai/claude-code"
                    )
                }
            } catch (e: CliStartException) {
                throw e
            } catch (_: Exception) {
                throw CliStartException(
                    "Claude CLI found at $cliPath but not responding. Try reinstalling:" +
                        " npm install -g @anthropic-ai/claude-code"
                )
            }

            // Check 3: Auth is available (delegated to AuthManager).
            // The AuthProvider checks System.getenv(), which is empty when IntelliJ
            // is launched from Finder/Dock. Fall back to checking the assembled
            // process env map which includes vars from CliEnvironment (shell capture
            // + plugin-env file).
            if (!authValidation.valid) {
                val envHasAuth = processEnv["CLAUDE_CODE_USE_BEDROCK"] == "1" ||
                    processEnv["ANTHROPIC_API_KEY"]?.isNotBlank() == true ||
                    processEnv["AWS_BEARER_TOKEN_BEDROCK"]?.isNotBlank() == true ||
                    processEnv["AWS_REGION"]?.isNotBlank() == true
                if (!envHasAuth) {
                    throw CliStartException(authValidation.message ?: "No authentication configured.")
                }
            }
        }

        internal fun tokenizeArgs(input: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var inQuote = false
            for (ch in input) {
                when {
                    ch == '"' -> inQuote = !inQuote
                    ch == ' ' && !inQuote -> {
                        if (current.isNotEmpty()) {
                            tokens.add(current.toString())
                            current.clear()
                        }
                    }
                    else -> current.append(ch)
                }
            }
            if (current.isNotEmpty()) tokens.add(current.toString())
            return tokens
        }

        internal fun sanitizeCliExtraArgs(tokens: List<String>): List<String> {
            val managedPermissionFlags = setOf(
                "--allowedTools",
                "--allowed-tools",
                "--disallowedTools",
                "--disallowed-tools",
                "--permission-mode",
                "--permission-prompt-tool",
                "--dangerously-skip-permissions",
                "--allow-dangerously-skip-permissions",
                "--setting-sources",
                "--settings",
            )
            val noValueFlags = setOf(
                "--dangerously-skip-permissions",
                "--allow-dangerously-skip-permissions",
            )
            val sanitized = mutableListOf<String>()
            var skipNext = false
            for (token in tokens) {
                if (skipNext) {
                    skipNext = false
                    continue
                }
                val flag = token.substringBefore("=")
                if (flag in managedPermissionFlags) {
                    skipNext = "=" !in token && flag !in noValueFlags
                    continue
                }
                sanitized.add(token)
            }
            return sanitized
        }

        internal fun buildModelArg(selected: String): List<String> =
            if (selected.isBlank()) emptyList() else listOf("--model", selected.trim())

        internal fun buildEffortArg(effort: String): List<String> =
            if (effort.isBlank()) emptyList() else listOf("--effort", effort.trim())

        internal fun buildSettingSourceArgs(): List<String> =
            listOf("--setting-sources", "user")

        /**
         * Map the user's tool-approval preference to CLI flags.
         *
         * - `confirm-all`  → no mode flag; [buildPermissionSettingsArgs] injects
         *                    session-only ask rules for read-only Bash commands
         *                    that Claude Code otherwise runs without prompting.
         * - `allow-safe`   → --permission-mode auto; Anthropic's native auto-mode
         *                    classifier auto-approves routine actions; soft-deny
         *                    cases fall through to the prompt tool.
         * - `allow-all`    → no CLI flag. We deliberately avoid
         *                    `--dangerously-skip-permissions`: enterprise policies
         *                    commonly strip it, and it's risky in general. Instead, the
         *                    prompt tool itself silently approves every request under
         *                    `allow-all` and emits a compact "auto-allowed" notice in
         *                    the chat transcript so the user can see what just ran.
         */
        internal fun buildPermissionArgs(toolApprovalMode: String): List<String> {
            return when (toolApprovalMode.trim()) {
                "allow-safe" -> listOf("--permission-mode", "auto")
                else -> emptyList() // confirm-all, allow-all, and unknown — all gating via prompt tool
            }
        }

        internal fun buildPermissionSettingsArgs(toolApprovalMode: String): List<String> {
            val mode = toolApprovalMode.trim()
            if (mode == "allow-safe" || mode == "allow-all") {
                return emptyList()
            }
            val askRules = listOf(
                "ls",
                "pwd",
                "cat",
                "head",
                "tail",
                "grep",
                "find",
                "wc",
                "diff",
                "stat",
                "du",
                "cd",
                "git",
            ).flatMap { command ->
                listOf("Bash($command)", "Bash($command *)")
            }.joinToString(",") { """"$it"""" }
            return listOf("--settings", """{"permissions":{"ask":[$askRules]}}""")
        }

        internal fun buildDisallowedTools(mcpAvailable: Boolean): String? {
            if (!mcpAvailable) return null
            // Disallow only the search tools where IntelliJ's PSI-backed alternatives are
            // strictly better. Edit/Write/MultiEdit/NotebookEdit are intentionally NOT
            // disallowed: --disallowedTools blocks the call but doesn't hide the tool
            // from Claude's tool list, so Claude often picks Write over propose_write
            // for bulk file creation. Letting Write succeed lets the EditReviewCoordinator
            // (Layer 2) capture the change post-hoc; the system prompt still asks Claude
            // to prefer propose_* for explicit pre-write review.
            return listOf("Grep", "Glob").joinToString(",")
        }

        internal fun buildSkillCatalogPrompt(skills: List<SkillInfo>): String {
            if (skills.isEmpty()) return ""
            val lines = skills.joinToString("\n") { "- ${it.qualifiedName}: ${it.description}" }
            return """
Available skills (invoke via slash command):
$lines

When a skill matches the user's task, suggest invoking it with /<skill-name>.
            """.trimIndent()
        }

        private val MCP_SYSTEM_PROMPT = """
You're running inside IntelliJ. The clawdea-intellij MCP server exposes the IDE's indices, content search, and debugger; its tools are pre-loaded — prefer them over Bash grep/find/ls and the Glob/Grep built-ins for code search.

Code-search tool routing:
- `find_files` — locate files by name (filename index).
- `find_usages` / `find_callers` / `find_implementations` / `find_supertypes` — symbol navigation backed by PSI.
- `search_text` — literal or regex content search across project sources. Use this for CLI flag literals, error messages, log strings, config keys, or any text that is not a code symbol. **This is the replacement for `Bash grep -rn` — do not shell out to grep when this tool fits.**

MANDATORY OUTPUT FORMAT — CLICKABLE CODE REFERENCES (chat replies only):
In your chat replies to the user, every class name, method name, field name, and file name MUST use this syntax:
{[ref:fully.qualified.search.query|visible label]}
Examples:
- {[ref:com.example.bookstore.BookService|BookService]} — class
- {[ref:com.example.bookstore.BookService.listAll|listAll]} — method
- {[ref:com.example.bookstore.BookService:42|BookService:42]} — class at line 42
- {[ref:ChatPanel.kt|ChatPanel.kt]} — file
- {[ref:ChatPanel.kt:84|ChatPanel.kt:84]} — file with line
- {[ref:ChatPanel.kt:84-120|ChatPanel.kt:84-120]} — file with line range (selects lines 84..120 on click)
- {[ref:src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt|CliProcess.kt]} — file path
Rules:
- The ref: query MUST use fully qualified class names (with package).
- The label is the short name the user sees.
- When citing a specific line, put `:line` in the ref: query, not only in the label. The label is display-only — putting `:42` in the label alone does NOT navigate to line 42.
- When citing a span of code, use `:startLine-endLine` in the ref: query (e.g. `:84-120`). Clicking will open the file and select the entire range.
- Use ref: links for ALL code symbols in chat replies. Never use bare `backtick` references when you can provide a ref: link.
- If you don't know the fully qualified name, use the short name as the query — the IDE will search for it.

This format is ONLY for chat replies. Files you write to disk (wiki pages, READMEs, source code, configs) use the file format's native conventions — standard markdown links `[label](path)` in `.md` files, plain `package.Class` references in Kotlin, etc. Do NOT embed `{[ref:...|...]}` in files you write.

When delegating to subagents, remind them: prefer the clawdea-intellij MCP tools over Bash grep/find/ls and the Glob/Grep built-ins.

Debug tool guidelines:
- Start a debug session before using stepping/inspection tools.
- All stepping tools block until the program suspends and return the new position.
- debug_resume returns the next suspend position or "running" if no breakpoint is hit within 10 seconds.
- You can only remove breakpoints you created. User breakpoints can be temporarily disabled with debug_disable_breakpoint.
- When done debugging, call debug_stop to clean up your breakpoints and restore any user breakpoints you disabled.
- Use debug_evaluate to test hypotheses. Use debug_set_value to modify variables at runtime to verify fix ideas without recompiling.
- When investigating runtime bugs, prefer setting a breakpoint and inspecting live state over guessing from static reads. Combine with code-index tools: indices to locate, debugger to observe.
        """.trimIndent()

        private val EDIT_REVIEW_PROMPT = """
File-edit routing:
For every file mutation, prefer the MCP propose_* tools — they open a diff dialog so the user can review (and reject) the change before it lands on disk:
- propose_edit — preferred over the built-in Edit tool. Single old_string → new_string substitution.
- propose_write — preferred over the built-in Write tool. Overwrites a file with new content.
- propose_multi_edit — preferred over the built-in MultiEdit tool. Takes file_path and edits as a JSON-encoded array of {old_string, new_string} objects.
- propose_notebook_edit — preferred over the built-in NotebookEdit tool. Same signature (notebook_path, cell_id, new_source, optional cell_type, optional edit_mode).

The built-in Edit/Write/MultiEdit/NotebookEdit tools also work; ClawDEA captures their content for post-hoc diff review. Use the propose_* variants whenever the user might want to inspect or reject the change before it applies (the default for new files and any non-trivial edit).
        """.trimIndent()

    }
}

private val resolveLog = Logger.getInstance("com.adobe.clawdea.cli.resolveClaudeCliPath")

fun resolveClaudeCliPath(configured: String): String {
    if (configured.isNotBlank() && configured != "claude") {
        return configured
    }
    val home = System.getProperty("user.home")
    val candidates = listOf(
        "$home/.local/bin/claude",
        "$home/.nvm/versions/node/default/bin/claude",
        "/usr/local/bin/claude",
        "/opt/homebrew/bin/claude",
    )
    for (candidate in candidates) {
        if (java.io.File(candidate).canExecute()) {
            resolveLog.info("Resolved claude CLI at: $candidate")
            return candidate
        }
    }
    return "claude"
}
