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
import com.adobe.clawdea.knowledge.prompts.PromptResource
import com.adobe.clawdea.mcp.McpServer
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
    private var systemPromptFile: java.io.File? = null
    private var settingsFile: java.io.File? = null
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
            val effectiveApprovalMode = project?.let { McpServer.getInstance(it).activeToolApprovalMode }
                ?: settings.toolApprovalMode
            command.addAll(buildPermissionArgs(effectiveApprovalMode))
            val settingsJson = buildPermissionSettingsJson(effectiveApprovalMode)
            if (settingsJson != null) {
                // Write to a temp file rather than passing inline. On Windows the CLI
                // entry point is `claude.cmd`, so Java's ProcessBuilder routes args
                // through cmd.exe — which strips inner double quotes and splits on
                // unquoted spaces. The inline JSON `{"permissions":{"ask":["Bash(ls
                // *)"]}}` becomes `{permissions:{ask:[Bash(ls` and the CLI treats it
                // as a file path. The file path form sidesteps the quoting problem
                // entirely and matches how we already handle the system prompt.
                val tmp = java.io.File.createTempFile("clawdea-settings-", ".json")
                tmp.deleteOnExit()
                tmp.writeText(settingsJson, StandardCharsets.UTF_8)
                settingsFile = tmp
                log.info("Wrote permission settings to ${tmp.absolutePath}")
                command.addAll(listOf("--settings", tmp.absolutePath))
            }
            command.addAll(listOf("--permission-prompt-tool", "mcp__clawdea-intellij__request_permission"))

            val mcpJson = com.adobe.clawdea.mcp.buildMcpClientConfigJson(mcpPort)
            val tmpFile = java.io.File.createTempFile("clawdea-mcp-", ".json")
            tmpFile.deleteOnExit()
            tmpFile.writeText(mcpJson)
            mcpConfigFile = tmpFile
            log.info("Wrote MCP config to ${tmpFile.absolutePath}")

            command.addAll(listOf("--mcp-config", tmpFile.absolutePath))

            if (settings.enableWikiLibrarian) {
                try {
                    val agentsJson = com.adobe.clawdea.knowledge.wiki.WikiAgentsArg.buildJson()
                    command.addAll(listOf("--agents", agentsJson))
                    log.info("Injected wiki-librarian + wiki-author subagents via --agents (${agentsJson.length} chars)")
                } catch (e: Throwable) {
                    log.warn("Failed to build wiki agents --agents arg; skipping injection", e)
                }
            }

            val disallowed = buildDisallowedTools(mcpAvailable = true)
            if (disallowed != null) {
                command.addAll(listOf("--disallowedTools", disallowed))
            }

            val systemPrompt = buildString {
                if (settings.enableWikiLibrarian) {
                    append(WIKI_LIBRARIAN_PROMPT)
                    append("\n\n")
                }
                append(MCP_SYSTEM_PROMPT)
                append("\n\n")
                append(EDIT_REVIEW_PROMPT)
                val baselineDefaults = buildBaselineDefaultsPrompt(settings.enableBaselineDefaults)
                if (baselineDefaults.isNotBlank()) {
                    append("\n\n")
                    append(baselineDefaults)
                }
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
            // Write to a temp file and use --append-system-prompt-file instead of
            // --append-system-prompt <inline>. With skill catalog + primer the
            // prompt routinely exceeds 32KB, which blows past Windows'
            // CreateProcess 32,767-char command-line limit and also risks ARG_MAX
            // on Linux for large primers. The file variant scales without either
            // constraint.
            val promptFile = java.io.File.createTempFile("clawdea-system-prompt-", ".txt")
            promptFile.deleteOnExit()
            promptFile.writeText(systemPrompt, StandardCharsets.UTF_8)
            systemPromptFile = promptFile
            log.info("Wrote system prompt (${systemPrompt.length} chars) to ${promptFile.absolutePath}")
            command.addAll(listOf("--append-system-prompt-file", promptFile.absolutePath))
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
        // Track in the process-global registry so the safety-net reaper can kill
        // this persistent CLI on plugin unload / IDE close even if the normal
        // Disposable chain doesn't run. stop() unregisters it.
        CliProcessRegistry.register(process!!)
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
            CliProcessRegistry.unregister(proc)
            try { stdoutReader?.close() } catch (_: Exception) {}
            try { mcpConfigFile?.delete() } catch (_: Exception) {}
            try { systemPromptFile?.delete() } catch (_: Exception) {}
            stderrThread?.let { t ->
                t.interrupt()
                try { t.join(1000) } catch (_: Exception) {}
            }
            process = null
            stdoutReader = null
            stdinWriter = null
            stderrThread = null
            mcpConfigFile = null
            systemPromptFile = null
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
            // Check 1: Binary exists and is launchable
            val file = java.io.File(cliPath)
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            val isWindows = osName.contains("windows")
            val launchable = if (isWindows) file.isFile else file.canExecute()
            if (cliPath == "claude" || !file.isFile || !launchable) {
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
         * - `confirm-all`  → no mode flag; [buildPermissionSettingsJson] injects
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

        internal fun buildPermissionSettingsJson(toolApprovalMode: String): String? {
            val mode = toolApprovalMode.trim()
            if (mode == "allow-safe" || mode == "allow-all") {
                return null
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
            return """{"permissions":{"ask":[$askRules]}}"""
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

        /**
         * Returns the always-on baseline working-defaults block for the system
         * prompt, or "" when [enabled] is false or the bundled resource cannot
         * be loaded. Pure and side-effect-free (no logging) so it can be unit
         * tested without launching a process — same shape as
         * [buildSkillCatalogPrompt]. Fail-soft: a missing resource degrades to
         * "feature off"; the packaging defect is caught by the unit test, not
         * at runtime.
         */
        internal fun buildBaselineDefaultsPrompt(enabled: Boolean): String {
            if (!enabled) return ""
            return try {
                PromptResource.load("baseline-defaults")
            } catch (_: Exception) {
                ""
            }
        }

        /**
         * Loads a static system-prompt resource, fail-soft to "" if the bundled
         * resource cannot be loaded. Applies `.trim()` to drop the resource
         * file's trailing newline, producing output byte-identical to the
         * previous inline `trimIndent()` literal (which stripped the leading and
         * trailing blank lines inside the `"""` block).
         *
         * Unlike [buildBaselineDefaultsPrompt] (a user-toggleable, additive
         * block), these are always-on core routing prompts, so a load failure is
         * logged at error level — it is a packaging defect, not a runtime
         * condition, and the unit tests guard resource presence at build time —
         * while still degrading soft rather than crashing CLI startup.
         */
        private fun loadStaticPrompt(name: String): String = try {
            PromptResource.load(name).trim()
        } catch (e: Exception) {
            Logger.getInstance("com.adobe.clawdea.cli.CliProcess").error(
                "Failed to load static system-prompt resource '$name'; " +
                    "continuing without it. This is a packaging defect — the resource " +
                    "should ship in src/main/resources/prompts/$name.md.",
                e,
            )
            ""
        }

        private val MCP_SYSTEM_PROMPT = loadStaticPrompt("mcp-system-prompt")

        private val WIKI_LIBRARIAN_PROMPT = loadStaticPrompt("wiki-librarian-prompt")

        private val EDIT_REVIEW_PROMPT = loadStaticPrompt("edit-review-prompt")

    }
}

private val resolveLog = Logger.getInstance("com.adobe.clawdea.cli.resolveClaudeCliPath")

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("windows")

/**
 * Redirect a `.ps1` path to its sibling `.cmd` shim. Java's ProcessBuilder can
 * launch `.cmd` / `.bat` / `.exe` directly via CreateProcess, but not `.ps1` —
 * those require `powershell -File ...`. npm's Windows installer drops all three
 * shims side-by-side (`claude`, `claude.cmd`, `claude.ps1`), so we transparently
 * prefer `.cmd` when the user (or the file picker) landed on `.ps1`.
 */
internal fun normalizeWindowsShimPath(path: String): String {
    if (!isWindows()) return path
    if (!path.endsWith(".ps1", ignoreCase = true)) return path
    val cmdCandidate = path.substring(0, path.length - 4) + ".cmd"
    if (java.io.File(cmdCandidate).isFile) {
        resolveLog.info("Redirecting .ps1 shim to .cmd sibling: $cmdCandidate")
        return cmdCandidate
    }
    val batCandidate = path.substring(0, path.length - 4) + ".bat"
    if (java.io.File(batCandidate).isFile) {
        resolveLog.info("Redirecting .ps1 shim to .bat sibling: $batCandidate")
        return batCandidate
    }
    return path
}

fun resolveClaudeCliPath(configured: String): String {
    if (configured.isNotBlank() && configured != "claude") {
        return normalizeWindowsShimPath(configured)
    }
    val home = System.getProperty("user.home")
    val candidates = if (isWindows()) {
        val appDataRoaming = System.getenv("APPDATA").orEmpty()
        val appDataLocal = System.getenv("LOCALAPPDATA").orEmpty()
        listOfNotNull(
            if (appDataRoaming.isNotBlank()) "$appDataRoaming\\npm\\claude.cmd" else null,
            if (appDataLocal.isNotBlank()) "$appDataLocal\\Volta\\bin\\claude.cmd" else null,
            "$home\\AppData\\Roaming\\npm\\claude.cmd",
            "$home\\AppData\\Local\\Volta\\bin\\claude.cmd",
            "$home\\.local\\bin\\claude.cmd",
            "C:\\Program Files\\nodejs\\claude.cmd",
        )
    } else {
        listOf(
            "$home/.local/bin/claude",
            "$home/.nvm/versions/node/default/bin/claude",
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
        )
    }
    for (candidate in candidates) {
        val file = java.io.File(candidate)
        // On Windows .cmd/.bat shims, canExecute() can return false even for a
        // launchable file — launchability is decided by CreateProcess + PATHEXT.
        // On Unix we still require the executable bit.
        val usable = if (isWindows()) file.isFile else file.canExecute()
        if (usable) {
            resolveLog.info("Resolved claude CLI at: $candidate")
            return candidate
        }
    }
    return "claude"
}
