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
    // Resolves the provider id whose pinned model feeds `--model`. Defaults to the global effective
    // provider (today's behavior) so non-factory callers are unaffected. AgentBackendFactory passes
    // the SELECTION's provider id so a per-tab CLAUDE tab reads the Claude-pinned model, not the
    // global provider's (which may be a Codex/OpenAI model → "invalid model" failure).
    private val providerIdProvider: () -> String = { AuthManager.getInstance().effectiveProviderId() },
    // The model id pinned by THIS tab's AgentSelection. When non-blank it is the authoritative
    // `--model` value: the same selection drives both the dropdown label and the CLI, so a fresh
    // chat whose default was set via the Roles tab (roleSelections, never written to the
    // selectedModels map) still launches the CLI on the shown model instead of the CLI's own
    // default. Blank means "no pin" → fall back to the settings-map read (subscription default, etc.).
    private val pinnedModelId: String = "",
) : AgentProcess {

    private val log = Logger.getInstance(CliProcess::class.java)

    private var process: Process? = null
    private var stdoutReader: BufferedReader? = null
    private var stdinWriter: BufferedWriter? = null
    private var stderrThread: Thread? = null
    private var mcpConfigFile: java.io.File? = null
    private var systemPromptFile: java.io.File? = null
    private var settingsFile: java.io.File? = null
    private val recentStderr = ConcurrentLinkedDeque<String>()

    override val isAlive: Boolean
        get() = process?.isAlive == true

    /**
     * Resolves the `--model` value for the CLI. The tab's [pinnedModelId] (from its AgentSelection)
     * wins when present, so the model shown in the dropdown is exactly the model the CLI runs. When
     * blank, falls back to the settings-map read for the provider this process was built for (via
     * [providerIdProvider]) rather than the global effective provider — a per-tab Claude tab thus
     * reads the Claude-pinned model even when the global default is a Codex/OpenAI provider.
     * Internal for headless testing of the selection→model routing.
     */
    internal fun resolveCliModel(settings: ClawDEASettings): String =
        pinnedModelId.ifBlank { settings.getCliModelId(workingDirectory, providerIdProvider()) }

    override fun start(resumeSessionId: String?, skills: List<SkillInfo>) {
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
                // as a file path. The file path form sidesteps /resumethe quoting problem
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
                    val wikiSel = com.adobe.clawdea.provider.RoleSelectionStore(ClawDEASettings.getInstance())
                        .get(com.adobe.clawdea.provider.AgentRole.WIKI)
                    val mode = com.adobe.clawdea.knowledge.wiki.chooseLibrarianMode(wikiSel)
                    val agentsJson = when (mode) {
                        com.adobe.clawdea.knowledge.wiki.LibrarianMode.CLAUDE_SUBAGENT ->
                            com.adobe.clawdea.knowledge.wiki.WikiAgentsArg.buildJson(wikiSel.modelId)
                        com.adobe.clawdea.knowledge.wiki.LibrarianMode.CLAUDE_SUBAGENT_FALLBACK ->
                            com.adobe.clawdea.knowledge.wiki.WikiAgentsArg.buildJson("")
                        com.adobe.clawdea.knowledge.wiki.LibrarianMode.AGENTIC_MCP_TOOL ->
                            com.adobe.clawdea.knowledge.wiki.WikiAgentsArg.buildAuthorOnlyJson()
                    }
                    command.addAll(listOf("--agents", agentsJson))
                    log.info("Injected wiki subagents via --agents (mode=$mode, ${agentsJson.length} chars)")
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
                    val wikiSel = com.adobe.clawdea.provider.RoleSelectionStore(ClawDEASettings.getInstance())
                        .get(com.adobe.clawdea.provider.AgentRole.WIKI)
                    val librarianPrompt = when (com.adobe.clawdea.knowledge.wiki.chooseLibrarianMode(wikiSel)) {
                        com.adobe.clawdea.knowledge.wiki.LibrarianMode.AGENTIC_MCP_TOOL -> WIKI_LIBRARIAN_TOOL_PROMPT
                        else -> WIKI_LIBRARIAN_PROMPT
                    }
                    append(librarianPrompt)
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

        val selectedModel = resolveCliModel(ClawDEASettings.getInstance())
        command.addAll(buildModelArg(selectedModel))

        val selectedEffort = ClawDEASettings.getInstance().getSelectedEffort(workingDirectory)
        command.addAll(buildEffortArg(selectedEffort))

        if (!resumeSessionId.isNullOrBlank()) {
            command.addAll(listOf("--resume", resumeSessionId))
        }

        if (settings.cliExtraArgs.isNotBlank()) {
            command.addAll(sanitizeCliExtraArgs(tokenizeArgs(settings.cliExtraArgs)))
        }

        // On Windows the resolved entry point is `claude.cmd`, which ProcessBuilder runs through
        // cmd.exe (8191-char command-line cap). The inline `--agents` JSON blows past that with
        // "The command line is too long.", so launch node + cli.js directly (CreateProcess, 32767).
        // No-op on non-Windows and when the shim can't be parsed. Regression: NodeDirectLaunchTest.
        val launchCommand = rewriteWindowsCmdToNodeLaunch(command)

        log.info("Starting CLI process: ${launchCommand.joinToString(" ")}")

        val pb = ProcessBuilder(launchCommand)
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

    override fun recentStderrLines(): List<String> = recentStderr.toList()

    internal fun resolveCliPath(configured: String): String = resolveClaudeCliPath(configured)

    override fun readLine(): String? {
        return try {
            stdoutReader?.readLine()
        } catch (e: Exception) {
            log.warn("Error reading from CLI stdout", e)
            null
        }
    }

    override fun writeLine(line: String) {
        try {
            stdinWriter?.write(line)
            stdinWriter?.newLine()
            stdinWriter?.flush()
        } catch (e: Exception) {
            log.warn("Error writing to CLI stdin", e)
        }
    }

    override fun sendInterrupt() {
        val proc = process ?: return
        try {
            val pid = proc.pid()
            Runtime.getRuntime().exec(arrayOf("kill", "-INT", pid.toString())).waitFor()
        } catch (e: Exception) {
            log.warn("Error sending SIGINT to CLI (pid=${proc.pid()})", e)
        }
    }

    override fun stop() {
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

        private val WIKI_LIBRARIAN_TOOL_PROMPT = loadStaticPrompt("wiki-librarian-tool-prompt")

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
    // Windows: none of the well-known install dirs matched (e.g. fnm / nvm-for-windows
    // / a custom prefix). ProcessBuilder on Windows does NOT replicate cmd.exe's
    // PATH+PATHEXT search, so spawning the bare name "claude" fails with
    // CreateProcess error=2 even when claude.cmd is on PATH. Resolve it ourselves to a
    // fully-qualified, launchable shim before falling back.
    if (isWindows()) {
        findClaudeOnWindowsPath(System.getenv("PATH").orEmpty(), System.getenv("PATHEXT").orEmpty())
            ?.let {
                resolveLog.info("Resolved claude CLI on PATH: $it")
                return it
            }
    }
    return "claude"
}

/**
 * Replicate cmd.exe's PATH + PATHEXT lookup for `claude`, returning the first launchable
 * fully-qualified shim (e.g. `…\claude.CMD`) or null if none is on PATH. ProcessBuilder can launch
 * such a path directly via CreateProcess; a bare "claude" it cannot. [pathExt] falls back to a
 * standard default when blank so the search is never disabled. [exists] is injected for testing.
 */
internal fun findClaudeOnWindowsPath(
    path: String,
    pathExt: String,
    exists: (String) -> Boolean = { java.io.File(it).isFile },
): String? = findBinaryOnWindowsPath("claude", path, pathExt, exists)

/**
 * Generalized cmd.exe PATH + PATHEXT lookup for [binary] (e.g. `claude`, `codex`). Returns the
 * first launchable fully-qualified shim or null if none is on PATH. See [findClaudeOnWindowsPath].
 */
internal fun findBinaryOnWindowsPath(
    binary: String,
    path: String,
    pathExt: String,
    exists: (String) -> Boolean = { java.io.File(it).isFile },
): String? {
    val exts = pathExt.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOf(".COM", ".EXE", ".BAT", ".CMD") }
    for (dir in path.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
        for (ext in exts) {
            val candidate = "$dir\\$binary$ext"
            if (exists(candidate)) return candidate
        }
    }
    return null
}

/**
 * Locate [binary] on a Unix colon-delimited [path] (e.g. the login-shell `PATH`). Returns the
 * first launchable absolute path or null. Mirrors [findBinaryOnWindowsPath] for POSIX; [isExecutable]
 * is injected for testing.
 */
internal fun findExecutableOnUnixPath(
    binary: String,
    path: String,
    isExecutable: (String) -> Boolean = { java.io.File(it).let { f -> f.isFile && f.canExecute() } },
): String? {
    for (dir in path.split(':').map { it.trim() }.filter { it.isNotEmpty() }) {
        val candidate = "$dir/$binary"
        if (isExecutable(candidate)) return candidate
    }
    return null
}

/**
 * Resolve the OpenAI `codex` CLI path, mirroring [resolveClaudeCliPath]: honor an explicit
 * user-configured path (normalizing Windows `.ps1` shims), otherwise probe the well-known npm /
 * nvm / homebrew install locations, then a Windows PATH+PATHEXT search, then (on Unix) the user's
 * login-shell `PATH` — which the JVM lacks on Finder/Dock launches, so a bare-name spawn fails with
 * "Cannot run program codex" even though codex is on the shell PATH — and finally the bare name
 * `"codex"`. [shellResolver] is injected so unit tests don't spawn a login shell.
 */
fun resolveCodexCliPath(
    configured: String,
    shellResolver: (String) -> String? = { CliEnvironment.resolveOnShellPath(it) },
): String {
    if (configured.isNotBlank() && configured != "codex") {
        return normalizeWindowsShimPath(configured)
    }
    val home = System.getProperty("user.home")
    val candidates = if (isWindows()) {
        val appDataRoaming = System.getenv("APPDATA").orEmpty()
        val appDataLocal = System.getenv("LOCALAPPDATA").orEmpty()
        listOfNotNull(
            if (appDataRoaming.isNotBlank()) "$appDataRoaming\\npm\\codex.cmd" else null,
            if (appDataLocal.isNotBlank()) "$appDataLocal\\Volta\\bin\\codex.cmd" else null,
            "$home\\AppData\\Roaming\\npm\\codex.cmd",
            "$home\\AppData\\Local\\Volta\\bin\\codex.cmd",
            "$home\\.local\\bin\\codex.cmd",
            "C:\\Program Files\\nodejs\\codex.cmd",
        )
    } else {
        listOf(
            "$home/.local/bin/codex",
            "$home/.nvm/versions/node/default/bin/codex",
            "/usr/local/bin/codex",
            "/opt/homebrew/bin/codex",
        )
    }
    for (candidate in candidates) {
        val file = java.io.File(candidate)
        val usable = if (isWindows()) file.isFile else file.canExecute()
        if (usable) {
            resolveLog.info("Resolved codex CLI at: $candidate")
            return candidate
        }
    }
    if (isWindows()) {
        findBinaryOnWindowsPath("codex", System.getenv("PATH").orEmpty(), System.getenv("PATHEXT").orEmpty())
            ?.let {
                resolveLog.info("Resolved codex CLI on PATH: $it")
                return it
            }
    } else {
        shellResolver("codex")?.let {
            resolveLog.info("Resolved codex CLI on shell PATH: $it")
            return it
        }
    }
    return "codex"
}

/**
 * Recover `[node, <abs cli.js>]` from a Windows npm `.cmd`/`.bat` shim so we can launch the CLI
 * through `node.exe` directly instead of via `cmd.exe`.
 *
 * Why: a `.cmd` shim is a batch file, so Java's ProcessBuilder routes it through cmd.exe, whose
 * command line is capped at 8191 chars. ClawDEA's `--agents` JSON (the wiki subagents) pushes the
 * line past that cap and the CLI dies with "The command line is too long." A native `node.exe`
 * launch goes through CreateProcess (32767-char limit) and clears it.
 *
 * Parsing mirrors npm's own `read-cmd-shim`: the exec line is `… "%dp0%\<target>" %*` (modern
 * cmd-shim) or `"%~dp0\<target>" %*` (legacy corepack style). We only redirect when the target is a
 * `.js` file. [shimDir] is the directory the shim lives in (its `%~dp0`); the returned cli.js path
 * is resolved against it. The node executable is `<shimDir>\node.exe` when [nodeExeExists] reports
 * it bundled (the npm layout), else bare `"node"` (resolved from PATH by node's own launcher).
 * Returns null when the text is not a recognizable JS shim, so callers fall back to the `.cmd`.
 */
internal fun nodeDirectLaunchPrefix(
    shimDir: String,
    shimText: String,
    nodeExeExists: (String) -> Boolean = { java.io.File(it).isFile },
): List<String>? {
    // Matches both `"%dp0%\target" %*` and `"%~dp0\target" %*`; captures the relative target.
    val target = Regex(""""%(?:~dp0|dp0%)\\([^"]+?)"\s+%\*""")
        .find(shimText)?.groupValues?.get(1)
        ?: return null
    if (!target.endsWith(".js", ignoreCase = true)) return null

    val cleanDir = shimDir.trimEnd('\\')
    val cliJs = "$cleanDir\\$target"
    val nodeExe = "$cleanDir\\node.exe"
    val node = if (nodeExeExists(nodeExe)) nodeExe else "node"
    return listOf(node, cliJs)
}

/**
 * If [command] launches a Windows `.cmd`/`.bat` claude shim, rewrite it to `[node, cli.js, …args]`
 * so the spawn bypasses cmd.exe's 8191-char command-line limit (see [nodeDirectLaunchPrefix]). Any
 * failure — not Windows, argv0 not a shim, unparseable shim, read error — returns [command]
 * unchanged so behavior degrades to the existing (cmd.exe) launch rather than breaking. [readShim]
 * and [nodeExeExists] are injected for testing.
 */
internal fun rewriteWindowsCmdToNodeLaunch(
    command: List<String>,
    isWindows: Boolean = isWindows(),
    readShim: (String) -> String = { java.io.File(it).readText() },
    nodeExeExists: (String) -> Boolean = { java.io.File(it).isFile },
): List<String> {
    if (!isWindows || command.isEmpty()) return command
    val argv0 = command[0]
    if (!argv0.endsWith(".cmd", ignoreCase = true) && !argv0.endsWith(".bat", ignoreCase = true)) {
        return command
    }
    // Extract the parent by backslash explicitly — java.io.File.parent keys off the host
    // separator, so on a non-Windows test host it would not split a `C:\…\claude.cmd` path.
    val shimDir = argv0.substringBeforeLast('\\', "").ifEmpty { return command }
    val prefix = try {
        nodeDirectLaunchPrefix(shimDir, readShim(argv0), nodeExeExists)
    } catch (e: Throwable) {
        resolveLog.info("Could not read claude shim '$argv0' for node-direct launch; using cmd.exe: ${e.message}")
        null
    } ?: return command
    resolveLog.info("Windows: launching claude via node directly to bypass cmd.exe arg limit: ${prefix.joinToString(" ")}")
    return prefix + command.drop(1)
}
