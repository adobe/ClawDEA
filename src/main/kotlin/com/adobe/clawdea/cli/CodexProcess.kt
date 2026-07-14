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
import com.adobe.clawdea.mcp.buildCodexMcpConfigArgs
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.skills.SkillInfo
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue

/**
 * [AgentProcess] backed by the OpenAI `codex` CLI.
 *
 * `codex exec` is **one-shot per turn** (the prompt is a positional arg; the process streams the
 * turn's NDJSON to stdout and exits), whereas [CliBridge] expects a persistent process it can read
 * across many turns. [CodexProcess] bridges that gap with a **persistent facade**: [start] arms the
 * session but spawns nothing; each [writeLine] (a Claude-format user message from
 * [CliBridge.sendMessage]) launches a fresh `codex exec` (or `codex exec resume <thread_id>`) and
 * pumps its stdout into a queue that [readLine] drains. [readLine] blocks between turns instead of
 * returning null, so the bridge's reader loop stays alive; only [stop] ends the stream.
 *
 * Command shape and required flags are pinned by the Phase-2 spike
 * (docs/superpowers/specs/2026-07-14-codex-interface-findings.md → ## Phase 2 spike closure):
 * `-s danger-full-access` + `approval_policy="never"` are required for MCP tool calls to execute
 * non-interactively; file-mutation safety comes from ClawDEA's own `propose_edit` MCP gating.
 */
class CodexProcess(
    private val workingDirectory: String,
    private val mcpPort: Int = 0,
    @Suppress("unused") private val project: Project? = null,
    private val cliPathProvider: () -> String = { resolveCodexCliPath(ClawDEASettings.getInstance().state.codexCliPath) },
    private val modelProvider: () -> String = {
        ClawDEASettings.getInstance().getCliModelId(workingDirectory, AuthManager.getInstance().effectiveProviderId())
    },
    private val effortProvider: () -> String = { ClawDEASettings.getInstance().getSelectedEffort(workingDirectory) },
    private val forceChatGptAuthProvider: () -> Boolean = {
        AuthManager.getInstance().effectiveProviderId() == "openai-subscription"
    },
    private val envProvider: () -> Map<String, String> = { defaultEnv() },
    private val spawner: (List<String>, String, Map<String, String>) -> Process = ::defaultSpawn,
    private val instructionsProvider: (Project?, List<SkillInfo>) -> String = CodexInstructions::build,
) : AgentProcess {

    private val log = Logger.getInstance(CodexProcess::class.java)

    // Reassigned per session in [start]. A persistent shared queue would let the STOP sentinel that
    // [stop] enqueues to unblock the *old* reader race the *new* reader after a stop()->start() (e.g.
    // resuming a session): whichever reader wins take() gets the STOP, and if that's the new reader
    // its readLine() returns null and CliBridge reports a bogus "CLI process exited unexpectedly"
    // before any turn runs. A fresh queue per session isolates each reader (take() binds to the queue
    // instance at call time), so the old reader drains its own STOP and the new one starts clean.
    @Volatile private var outQueue = LinkedBlockingQueue<String>()
    private val recentStderr = ConcurrentLinkedDeque<String>()
    private val turnLock = Any()

    @Volatile private var aliveFlag = false
    @Volatile private var current: Process? = null

    /** thread_id of the codex session; drives `exec resume` for every turn after the first. */
    @Volatile private var lastThreadId: String? = null

    /** Skills handed to [start]; injected into the first-turn preamble (parity with Claude). */
    @Volatile private var sessionSkills: List<SkillInfo> = emptyList()

    /** True once the in-flight turn has emitted at least one codex event (distinguishes a real turn
     *  from a resume that died before producing anything). Reset at each [spawnTurn]. */
    @Volatile private var turnEmittedEvent = false

    /** True when codex reported the resume thread doesn't exist ("no rollout found"). Reset per turn. */
    @Volatile private var resumeFailureSeen = false

    override val isAlive: Boolean
        get() = aliveFlag

    override fun start(resumeSessionId: String?, skills: List<SkillInfo>) {
        // Fresh per-session queue — see the [outQueue] field comment (prevents a stale STOP from a
        // prior stop() leaking a spurious end-of-stream into this session's reader).
        outQueue = LinkedBlockingQueue()
        aliveFlag = true
        lastThreadId = resumeSessionId?.takeIf { it.isNotBlank() }
        // Retained for the first-turn preamble (tooling/edit-routing + skill catalog + primer).
        sessionSkills = skills
        // Do not spawn until the first user message arrives via writeLine.
    }

    override fun writeLine(line: String) {
        if (!aliveFlag) return
        val prompt = extractUserText(line)
        if (prompt.isNullOrBlank()) {
            log.warn("CodexProcess.writeLine: could not extract prompt from: ${line.take(200)}")
            return
        }
        spawnTurn(prompt)
    }

    private fun spawnTurn(prompt: String) {
        synchronized(turnLock) {
            current?.let { if (it.isAlive) it.destroy() }
            turnEmittedEvent = false
            resumeFailureSeen = false
            // First turn (no thread yet): prepend the standing instructions so codex has the
            // same tooling/edit-routing + skills + primer context Claude gets. Instructions
            // persist for the thread, so resume turns send only the user's prompt.
            val firstTurn = lastThreadId.isNullOrBlank()
            val wasResume = !firstTurn
            val effectivePrompt =
                if (firstTurn) CodexInstructions.prepend(instructionsProvider(project, sessionSkills), prompt)
                else prompt
            val command = buildCommand(
                cliPath = cliPathProvider(),
                model = modelProvider(),
                effort = effortProvider(),
                mcpPort = mcpPort,
                workingDirectory = workingDirectory,
                prompt = effectivePrompt,
                resumeThreadId = lastThreadId,
                forceChatGptAuth = forceChatGptAuthProvider(),
            )
            log.info("Starting codex turn: ${command.joinToString(" ")}")
            val turnEnv = envProvider()
            logAuthDiagnostics(turnEnv)
            val proc = try {
                spawner(command, workingDirectory, turnEnv)
            } catch (e: Exception) {
                log.warn("Failed to spawn codex process", e)
                aliveFlag = false
                outQueue.put(STOP)
                return
            }
            current = proc
            // codex exec reads the prompt from the arg but still blocks reading stdin until EOF
            // when stdin is a pipe — close it immediately so the turn proceeds.
            try { proc.outputStream.close() } catch (_: Exception) {}
            pumpStderr(proc)
            pumpStdout(proc, wasResume, prompt)
        }
    }

    private fun pumpStdout(proc: Process, wasResume: Boolean, rawPrompt: String) {
        Thread({
            try {
                BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.forEachLine { raw ->
                        val line = raw.trim()
                        // codex prints non-JSON noise ("Reading additional input from stdin...")
                        // to stdout/stderr; only JSON objects are events.
                        if (line.startsWith("{")) {
                            sniffThreadId(line)
                            if (line.contains("\"type\":\"error\"") || line.contains("turn.failed")) {
                                log.warn("codex error event: $line")
                            }
                            if (aliveFlag) {
                                turnEmittedEvent = true
                                outQueue.put(line)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.debug("codex stdout pump ended: ${e.message}")
            }
            // Turn finished. If this was a *resume* that died before emitting anything because codex
            // has no such thread (stale/foreign thread id — e.g. a Claude session id, or an expired
            // codex thread), the thread id is unusable. Drop it and retry the same prompt as a fresh
            // session so the user still gets an answer instead of a dead turn. Retrying is safe: the
            // fresh turn is not a resume, so it can't recurse here.
            // The resume error is on stderr; stdout hits EOF ~simultaneously, so wait briefly for the
            // process to exit and the stderr pump to surface it (only in the no-output resume case).
            if (wasResume && !turnEmittedEvent) {
                try { proc.waitFor() } catch (_: InterruptedException) {}
                var waitedMs = 0
                while (!resumeFailureSeen && waitedMs < RESUME_FAILURE_GRACE_MS) {
                    Thread.sleep(25); waitedMs += 25
                }
            }
            if (shouldRetryFresh(wasResume)) {
                log.info("codex resume failed (no rollout found); retrying prompt on a fresh thread")
                lastThreadId = null
                if (aliveFlag) spawnTurn(rawPrompt)
            }
            // Otherwise do NOT signal end-of-stream: the session persists and the next
            // writeLine spawns another turn. Only stop() enqueues STOP.
        }, "ClawDEA-Codex-stdout").apply { isDaemon = true }.start()
    }

    /** A resume turn that produced no events and whose stderr says the thread is gone must retry fresh. */
    private fun shouldRetryFresh(wasResume: Boolean): Boolean =
        wasResume && aliveFlag && !turnEmittedEvent && resumeFailureSeen

    private fun pumpStderr(proc: Process) {
        Thread({
            try {
                BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        recentStderr.addLast(line)
                        while (recentStderr.size > MAX_STDERR_LINES) recentStderr.pollFirst()
                        if (isResumeFailure(line)) resumeFailureSeen = true
                    }
                }
            } catch (_: Exception) {}
        }, "ClawDEA-Codex-stderr").apply { isDaemon = true }.start()
    }

    /**
     * One-line, secret-free snapshot of the codex auth-relevant environment. codex chooses API-key
     * mode vs ChatGPT mode from `OPENAI_API_KEY` + `~/.codex/auth.json` (found via HOME/CODEX_HOME);
     * when it silently lands in API-key mode, a ChatGPT-only model (e.g. `gpt-5.6-sol`) returns a
     * 400 "provided model identifier is invalid". Logging the resolved env makes that diagnosable.
     */
    private fun logAuthDiagnostics(env: Map<String, String>) {
        val keyPresent = !env["OPENAI_API_KEY"].isNullOrEmpty()
        val home = env["HOME"] ?: "<unset>"
        val codexHome = env["CODEX_HOME"] ?: "<unset>"
        val provider = try { AuthManager.getInstance().effectiveProviderId() } catch (_: Exception) { "<err>" }
        log.info(
            "codex auth env: provider=$provider OPENAI_API_KEY=${if (keyPresent) "present" else "absent"} " +
                "HOME=$home CODEX_HOME=$codexHome"
        )
    }

    private fun sniffThreadId(line: String) {
        if (!line.contains("thread.started")) return
        try {
            val obj = JsonParser.parseString(line).asJsonObject
            obj.get("thread_id")?.takeIf { it.isJsonPrimitive }?.asString?.let { lastThreadId = it }
        } catch (_: Exception) {}
    }

    override fun readLine(): String? {
        val v = outQueue.take()
        return if (v === STOP) null else v
    }

    override fun sendInterrupt() {
        // Kill the in-flight turn but keep the session facade alive so a subsequent
        // writeLine can resume the thread (mirrors Claude's SIGINT pause semantics).
        synchronized(turnLock) { current?.let { if (it.isAlive) it.destroy() } }
    }

    override fun stop() {
        aliveFlag = false
        synchronized(turnLock) {
            current?.let { if (it.isAlive) it.destroyForcibly() }
            current = null
        }
        // Unblock a reader parked in readLine().
        outQueue.put(STOP)
    }

    override fun recentStderrLines(): List<String> = recentStderr.toList()

    companion object {
        private val STOP = "\u0000__CODEX_STOP__"
        private const val MAX_STDERR_LINES = 200

        /** How long the stdout pump waits for the stderr pump to surface a resume failure. */
        private const val RESUME_FAILURE_GRACE_MS = 750

        /**
         * True when a codex stderr line signals the resume thread doesn't exist. codex prints e.g.
         * `Error: thread/resume: thread/resume failed: no rollout found for thread id <id> (code -32600)`
         * when asked to resume a thread it has no rollout for (a foreign/Claude id or an expired thread).
         */
        internal fun isResumeFailure(stderrLine: String): Boolean {
            val l = stderrLine.lowercase()
            return l.contains("no rollout found") ||
                (l.contains("thread/resume") && l.contains("failed"))
        }

        /**
         * Builds the `codex exec` command for one turn. Identical prefix for first and resume
         * turns; the resume subcommand + thread_id are appended just before the prompt.
         * Verified flag placement: exec-level flags (`--json`, `-s`) precede the `resume`
         * subcommand (see spike closure).
         */
        internal fun buildCommand(
            cliPath: String,
            model: String,
            effort: String,
            mcpPort: Int,
            workingDirectory: String,
            prompt: String,
            resumeThreadId: String?,
            forceChatGptAuth: Boolean = false,
        ): List<String> {
            val cmd = mutableListOf(
                cliPath, "exec",
                "--json",
                "--skip-git-repo-check",
                "-s", "danger-full-access",
                "-c", "approval_policy=\"never\"",
            )
            // Pin codex to the ChatGPT credential for the subscription provider. Without this, a
            // stray/valid OPENAI_API_KEY or an auth.json that codex can't resolve lets codex fall
            // into API-key mode, where ChatGPT-only account models (e.g. gpt-5.6-sol) return
            // HTTP 400 "provided model identifier is invalid". The API-key provider omits this so
            // it keeps using the key.
            if (forceChatGptAuth) cmd += listOf("-c", "preferred_auth_method=\"chatgpt\"")
            if (mcpPort > 0) cmd += buildCodexMcpConfigArgs(mcpPort)
            if (model.isNotBlank() && model != "default") cmd += listOf("-m", model)
            mapEffort(effort)?.let { cmd += listOf("-c", "model_reasoning_effort=\"$it\"") }
            cmd += listOf("-C", workingDirectory)
            if (!resumeThreadId.isNullOrBlank()) cmd += listOf("resume", resumeThreadId)
            cmd += prompt
            return cmd
        }

        /**
         * Maps ClawDEA's effort dropdown to codex's `model_reasoning_effort` enum
         * (`minimal|low|medium|high`). `xhigh`/`max` have no codex equivalent — collapse to `high`.
         * A blank/"default" effort omits the override (codex uses the model default).
         */
        internal fun mapEffort(effort: String): String? = when (effort.trim().lowercase()) {
            "minimal" -> "minimal"
            "low" -> "low"
            "medium" -> "medium"
            "high", "xhigh", "max" -> "high"
            else -> null
        }

        /**
         * Extracts the user prompt text from the Claude-format user message JSON that
         * [CliBridge.sendMessage] writes (`{"type":"user","message":{"content":"..."}}`).
         */
        internal fun extractUserText(json: String): String? {
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                val message = obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
                message.get("content")?.takeIf { it.isJsonPrimitive }?.asString
            } catch (_: Exception) {
                null
            }
        }

        private fun defaultEnv(): Map<String, String> {
            val env = mutableMapOf<String, String>()
            CliEnvironment.applyTo(env)
            for ((k, v) in System.getenv()) env.putIfAbsent(k, v)
            AuthManager.getInstance().applyToEnvironment(env)
            return env
        }

        private fun defaultSpawn(command: List<String>, workingDir: String, env: Map<String, String>): Process {
            val pb = ProcessBuilder(command)
                .directory(java.io.File(workingDir))
                .redirectErrorStream(false)
            val processEnv = pb.environment()
            processEnv.clear()
            processEnv.putAll(env)
            return pb.start()
        }
    }
}
