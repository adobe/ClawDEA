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
import com.adobe.clawdea.cost.CodexRateLimitMapper
import com.adobe.clawdea.cost.CostTracker
import com.adobe.clawdea.mcp.CODEX_MCP_SERVER_NAME
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.skills.SkillInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * [AgentProcess] backed by the OpenAI `codex app-server` (a long-lived JSON-RPC 2.0 peer spoken over
 * stdio, framed as newline-delimited JSON). This replaces the earlier one-shot `codex exec` facade:
 * a **single** app-server process now spans the whole session, giving per-token streaming
 * (`item/agentMessage/delta`), a native interrupt (`turn/interrupt`), native mid-turn steering
 * (`turn/steer`, see [steer]), server-driven approvals, and `workspace-write` MCP execution
 * without `danger-full-access`.
 *
 * ### Lifecycle
 *  - [start] spawns `codex app-server --stdio`, then drives the handshake asynchronously on the
 *    reader thread: `initialize` (request) → `initialized` (notification) → `thread/start` (or
 *    `thread/resume`), carrying [CodexInstructions] as `baseInstructions` and ClawDEA's local MCP
 *    server in `config.mcp_servers`. The thread id from the response drives [sendInterrupt] and
 *    cross-turn continuity.
 *  - [writeLine] (a Claude-format user message from [CliBridge.sendMessage]) issues one `turn/start`.
 *    Because instructions live on the thread, only the raw user text is sent per turn.
 *  - Server → client requests are handled without surfacing to the bridge: shell-command and
 *    file-patch approvals are routed through ClawDEA's permission gate ([CodexApprovalGate], Phase C)
 *    on a worker thread (honoring the tool-approval mode + "Auto-accept edits"); elicitation /
 *    user-input requests are accepted inline. With no [approvalGate] (no project / under test)
 *    everything is auto-approved.
 *  - Server → client *notifications* are forwarded verbatim to [readLine]; [CodexAppServerParser]
 *    maps the relevant ones to [CliEvent]s. The exception is `account/rateLimits/updated`, which is
 *    consumed here and fed straight to [CostTracker] (the real-credits Cost Control gauge) rather
 *    than the chat stream; the same snapshot is pulled once at thread start via `account/rateLimits/read`.
 *  - [readLine] blocks between turns (the process is genuinely persistent), so the bridge's reader
 *    loop stays alive until [stop] enqueues the end sentinel or the process dies.
 */
class CodexAppServerProcess(
    private val workingDirectory: String,
    private val mcpPort: Int = 0,
    private val project: Project? = null,
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
    /**
     * Routes codex's own shell/patch approval requests through ClawDEA's permission gate (Phase C).
     * Null → auto-approve everything (the Phase-B behavior; also the default when there is no
     * [project] to reach the gate, e.g. under test).
     */
    private val approvalGate: CodexApprovalGate? = project?.let { CodexApprovalGate.forProject(it) },
) : AgentProcess {

    private val log = Logger.getInstance(CodexAppServerProcess::class.java)

    // Approvals block on the user, so they run off the stdout reader thread — otherwise the reader
    // couldn't pump the `item/started` that renders the tool block the approval card attaches to.
    // Daemon threads so a pending prompt never blocks IDE shutdown.
    //
    // A `var` recreated on [start]: [stop] calls shutdownNow(), so a stop()->start() cycle (model
    // switch, /resume, wake-recovery) would otherwise leave a terminated executor. Submitting an
    // approval to it then throws RejectedExecutionException, the request is silently dropped, and
    // the codex app-server hangs waiting for a reply it never gets — surfacing as an IDE freeze.
    @Volatile private var approvalExecutor = newApprovalExecutor()

    private fun newApprovalExecutor(): java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "ClawDEA-CodexApproval").apply { isDaemon = true }
        }

    // Fresh per session: a persistent queue would let a STOP sentinel from a prior stop() unblock the
    // next session's reader (after a stop()->start(), e.g. a resume) before any turn runs.
    @Volatile private var outQueue = LinkedBlockingQueue<String>()
    private val recentStderr = ConcurrentLinkedDeque<String>()

    private val writeLock = Any()
    private val idCounter = AtomicLong(0)

    @Volatile private var aliveFlag = false
    @Volatile private var proc: Process? = null
    @Volatile private var stdin: Writer? = null

    // Handshake / turn correlation. Set from the reader thread; read under [writeLock] when sending.
    @Volatile private var initializeId: Long = -1
    @Volatile private var threadOpId: Long = -1
    @Volatile private var turnStartId: Long = -1
    @Volatile private var steerId: Long = -1
    @Volatile private var rateLimitsReadId: Long = -1
    @Volatile private var threadId: String? = null
    @Volatile private var currentTurnId: String? = null

    /**
     * True between a turn's `turn/start` (or `turn/steer`) acknowledgement and its terminal
     * `turn/completed` / `error`. Gates [steer]: codex only accepts `turn/steer` while a turn
     * is genuinely in flight (otherwise it errors `activeTurnNotSteerable` / unknown turn).
     */
    @Volatile private var turnActive = false

    /** Non-blank when this session was asked to resume a codex thread; drives thread/resume. */
    @Volatile private var resumeThreadId: String? = null

    /** True while the in-flight thread op is a resume (so a failure can fall back to a fresh start). */
    @Volatile private var resumeInFlight = false

    /** Skills for the first-turn preamble (parity with Claude's system prompt). */
    @Volatile private var sessionSkills: List<SkillInfo> = emptyList()

    /** A user prompt that arrived before the thread was ready; sent as soon as [threadId] is set. */
    @Volatile private var pendingPrompt: String? = null

    override val isAlive: Boolean
        get() = aliveFlag && (proc?.isAlive ?: false)

    override fun start(resumeSessionId: String?, skills: List<SkillInfo>) {
        outQueue = LinkedBlockingQueue()
        // A prior stop() shuts the executor down permanently; revive it for this session so
        // approvals can still be dispatched off the reader thread after a restart.
        if (approvalExecutor.isShutdown) approvalExecutor = newApprovalExecutor()
        threadId = null
        currentTurnId = null
        turnActive = false
        pendingPrompt = null
        resumeThreadId = resumeSessionId?.takeIf { it.isNotBlank() }
        resumeInFlight = false
        sessionSkills = skills

        val command = listOf(cliPathProvider(), "app-server").plus(launchOptions()).plus("--stdio")
        log.info("Starting codex app-server: ${command.joinToString(" ")}")
        val env = envProvider()
        val process = try {
            spawner(command, workingDirectory, env)
        } catch (e: Exception) {
            log.warn("Failed to spawn codex app-server", e)
            aliveFlag = false
            enqueueError("Failed to start codex app-server: ${e.message}")
            outQueue.put(STOP)
            return
        }
        proc = process
        stdin = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)
        aliveFlag = true

        pumpStderr(process)
        pumpStdout(process)

        // Kick off the handshake; the reader thread carries it forward on the initialize response.
        initializeId = sendRequest("initialize", JsonObject().apply {
            add("clientInfo", JsonObject().apply {
                addProperty("name", "ClawDEA")
                addProperty("version", CLIENT_VERSION)
            })
        })
    }

    /** Process-level `-c` overrides. Auth mode is pinned here (per-thread config can't select it). */
    private fun launchOptions(): List<String> =
        if (forceChatGptAuthProvider()) listOf("-c", "preferred_auth_method=\"chatgpt\"") else emptyList()

    override fun writeLine(line: String) {
        if (!aliveFlag) return
        val prompt = extractUserText(line)
        if (prompt.isNullOrBlank()) {
            log.warn("CodexAppServerProcess.writeLine: could not extract prompt from: ${line.take(200)}")
            return
        }
        synchronized(writeLock) {
            if (threadId != null) startTurn(prompt) else pendingPrompt = prompt
        }
    }

    private fun startTurn(prompt: String) {
        val tid = threadId ?: run { pendingPrompt = prompt; return }
        val input = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", prompt)
            })
        }
        val params = JsonObject().apply {
            addProperty("threadId", tid)
            add("input", input)
            val model = modelProvider()
            if (model.isNotBlank() && model != "default") addProperty("model", model)
            mapEffort(effortProvider())?.let { addProperty("effort", it) }
        }
        turnStartId = sendRequest("turn/start", params)
    }

    /** Reader-thread handler for a response to one of our requests. */
    private fun handleResponse(obj: JsonObject) {
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return
        val error = obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
        when (id) {
            initializeId -> {
                if (error != null) {
                    enqueueError(errorMessage(error))
                    return
                }
                sendNotification("initialized", JsonObject())
                startOrResumeThread()
            }
            threadOpId -> {
                if (error != null) {
                    if (resumeInFlight) {
                        // The thread is gone (foreign/Claude id or expired). Retry as a fresh thread
                        // so the user still gets an answer — the replayed transcript (if any) is
                        // already prepended to the first prompt by CliBridge.
                        log.info("codex thread/resume failed (${errorMessage(error)}); starting fresh")
                        resumeInFlight = false
                        resumeThreadId = null
                        startOrResumeThread()
                    } else {
                        enqueueError(errorMessage(error))
                    }
                    return
                }
                resumeInFlight = false
                val result = obj.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                val newThreadId = result?.get("thread")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                if (!newThreadId.isNullOrBlank()) {
                    threadId = newThreadId
                    synchronized(writeLock) {
                        pendingPrompt?.let { startTurn(it); pendingPrompt = null }
                    }
                    // Pull the current credit balance / rate-limit windows now so the Cost Control
                    // gauge is populated before the first turn (the `.../updated` notifications only
                    // arrive during a turn). Best-effort: errors are ignored. Skipped without a
                    // project (nowhere to push the snapshot), which also keeps the RPC stream
                    // deterministic under test.
                    if (project != null) {
                        rateLimitsReadId = sendRequest("account/rateLimits/read", JsonObject())
                    }
                }
            }
            rateLimitsReadId -> {
                if (error != null) return // best-effort; the notification stream still updates us
                val rl = obj.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("rateLimits")?.takeIf { it.isJsonObject }?.asJsonObject
                if (rl != null) pushRateLimits(rl)
            }
            turnStartId -> {
                if (error != null) {
                    turnActive = false
                    enqueueError(errorMessage(error))
                    return
                }
                val turn = obj.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("turn")?.takeIf { it.isJsonObject }?.asJsonObject
                currentTurnId = turn?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                turnActive = true
            }
            steerId -> {
                // turn/steer → { turnId }. The steer may spawn a fresh turn id; track it so a
                // later interrupt targets the right turn. A steer error just means the turn was
                // no longer steerable (raced a completion) — leave the stream to end normally.
                if (error != null) return
                val newTurnId = obj.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("turnId")?.takeIf { it.isJsonPrimitive }?.asString
                if (!newTurnId.isNullOrBlank()) currentTurnId = newTurnId
            }
        }
    }

    private fun startOrResumeThread() {
        val resume = resumeThreadId
        val config = buildThreadConfig()
        if (!resume.isNullOrBlank()) {
            resumeInFlight = true
            threadOpId = sendRequest("thread/resume", JsonObject().apply {
                addProperty("threadId", resume)
                addProperty("cwd", workingDirectory)
                if (config.size() > 0) add("config", config)
            })
            return
        }
        // Build the standing instructions off the EDT (this runs on the reader thread).
        val baseInstructions = try {
            instructionsProvider(project, sessionSkills)
        } catch (e: Exception) {
            log.warn("codex baseInstructions build failed; continuing without them", e)
            ""
        }
        threadOpId = sendRequest("thread/start", JsonObject().apply {
            addProperty("cwd", workingDirectory)
            addProperty("approvalPolicy", "on-request")
            addProperty("sandbox", "workspace-write")
            if (baseInstructions.isNotBlank()) addProperty("baseInstructions", baseInstructions)
            if (config.size() > 0) add("config", config)
        })
    }

    /** `config` overrides applied to the thread — ClawDEA's local MCP server + reasoning summary. */
    private fun buildThreadConfig(): JsonObject {
        val config = JsonObject()
        if (mcpPort > 0) {
            val servers = JsonObject().apply {
                add(CODEX_MCP_SERVER_NAME, JsonObject().apply {
                    addProperty("url", "http://127.0.0.1:$mcpPort/mcp")
                })
            }
            config.add("mcp_servers", servers)
        }
        // Stream the model's reasoning so the chat renders it in the "Thinking" block. The app-server
        // emits `item/reasoning/summaryTextDelta` (→ CliEvent.ReasoningDelta) ONLY when a concrete
        // summary level is requested — the default ("auto") yields an empty reasoning item with no
        // deltas for the ChatGPT-subscription flow, and "none"/unset streams nothing at all. "detailed"
        // reliably streams the summary. (Verified against codex-cli 0.144.4 app-server.)
        config.addProperty("model_reasoning_summary", REASONING_SUMMARY_LEVEL)
        return config
    }

    /**
     * Reader-thread handler for a server → client request. Command / file-patch approvals are
     * routed through [approvalGate] (Phase C) on a worker thread so the reader keeps pumping;
     * elicitation / user-input requests are accepted inline (our MCP server is trusted). When
     * [approvalGate] is null everything is auto-approved (Phase-B / no-project behavior).
     */
    private fun handleServerRequest(obj: JsonObject, method: String) {
        val id = obj.get("id") ?: return
        when (method) {
            "item/commandExecution/requestApproval" -> {
                val gate = approvalGate
                if (gate == null) { sendDecision(id, accept = true); return }
                val command = extractCommand(obj)
                val toolUseId = extractItemId(obj)
                gateAsync { sendDecision(id, accept = gate.approveCommand(command, toolUseId)) }
            }
            "item/fileChange/requestApproval" -> {
                val gate = approvalGate
                if (gate == null) { sendDecision(id, accept = true); return }
                val paths = extractChangePaths(obj)
                val toolUseId = extractItemId(obj)
                gateAsync { sendDecision(id, accept = gate.approveFileChange(paths, toolUseId)) }
            }
            "mcpServer/elicitation/request", "item/tool/requestUserInput" ->
                sendResult(id, JsonObject().apply { addProperty("action", "accept") })
            else ->
                // Unknown/legacy gate (e.g. v1 permissionsRequestApproval). Accept to avoid stalling.
                sendResult(id, JsonObject().apply { addProperty("decision", "approved") })
        }
    }

    /** Runs a blocking approval decision off the reader thread; failures fall back to accept. */
    private fun gateAsync(block: () -> Unit) {
        try {
            approvalExecutor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    log.warn("codex approval failed; leaving request unanswered: ${e.message}")
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Executor already shut down (process stopping) — nothing to answer.
            log.debug("codex approval skipped (executor shut down): ${e.message}")
        }
    }

    /** Replies to a v2 approval request. accept → "accept"; deny → "deny". */
    private fun sendDecision(id: com.google.gson.JsonElement, accept: Boolean) {
        sendResult(id, JsonObject().apply { addProperty("decision", if (accept) "accept" else "deny") })
    }

    private fun pumpStdout(process: Process) {
        Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.forEachLine { raw ->
                        val line = raw.trim()
                        if (!line.startsWith("{")) return@forEachLine
                        val obj = try {
                            JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
                        } catch (_: Exception) {
                            null
                        } ?: return@forEachLine
                        val method = obj.get("method")?.takeIf { it.isJsonPrimitive }?.asString
                        val hasId = obj.has("id") && !obj.get("id").isJsonNull
                        when {
                            // Account credit/rate-limit updates feed the Cost Control gauge directly,
                            // not the chat stream — consume here rather than forward to the parser.
                            method == "account/rateLimits/updated" -> handleRateLimitsNotification(obj)
                            method != null && hasId -> handleServerRequest(obj, method)
                            method != null -> {
                                // A terminal turn notification closes the steer window before the
                                // event is forwarded to the parser.
                                if (method == "turn/completed" || method == "error") turnActive = false
                                if (aliveFlag) outQueue.put(line) // notification → parser
                            }
                            hasId -> handleResponse(obj)
                            // else: malformed; ignore.
                        }
                    }
                }
            } catch (e: Exception) {
                log.debug("codex app-server stdout pump ended: ${e.message}")
            }
            // Process ended. Wake any parked reader so CliBridge observes the exit.
            if (aliveFlag) outQueue.put(STOP)
        }, "ClawDEA-CodexAppServer-stdout").apply { isDaemon = true }.start()
    }

    /** `account/rateLimits/updated` → map the `rateLimits` snapshot into the Cost Control gauge. */
    private fun handleRateLimitsNotification(obj: JsonObject) {
        val rl = obj.get("params")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("rateLimits")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        pushRateLimits(rl)
    }

    /** Maps a codex `RateLimitSnapshot` and pushes it to [CostTracker] for the openai-subscription card. */
    private fun pushRateLimits(rateLimits: JsonObject) {
        val proj = project ?: return
        val usage = CodexRateLimitMapper.map(rateLimits) ?: return
        try {
            CostTracker.getInstance(proj).updateOpenAiUsage(usage)
        } catch (e: Exception) {
            log.debug("codex rate-limit push failed: ${e.message}")
        }
    }

    private fun pumpStderr(process: Process) {
        Thread({
            try {
                BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        recentStderr.addLast(line)
                        while (recentStderr.size > MAX_STDERR_LINES) recentStderr.pollFirst()
                    }
                }
            } catch (_: Exception) {}
        }, "ClawDEA-CodexAppServer-stderr").apply { isDaemon = true }.start()
    }

    override fun readLine(): String? {
        val v = outQueue.take()
        return if (v === STOP) null else v
    }

    override fun sendInterrupt() {
        val tid = threadId ?: return
        val turn = currentTurnId ?: return
        turnActive = false
        sendRequest("turn/interrupt", JsonObject().apply {
            addProperty("threadId", tid)
            addProperty("turnId", turn)
        })
    }

    override val supportsSteer: Boolean get() = true

    /**
     * Native mid-turn steer: injects [text] into the live turn via `turn/steer` (the codex
     * app-server keeps generating and folds the new guidance in). Returns false when no turn is
     * steerable so [CliBridge] falls back to a fresh `turn/start`.
     */
    override fun steer(text: String): Boolean {
        if (!aliveFlag || text.isBlank()) return false
        val tid = threadId ?: return false
        if (!turnActive) return false
        val input = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
        }
        steerId = sendRequest("turn/steer", JsonObject().apply {
            addProperty("threadId", tid)
            add("input", input)
        })
        return true
    }

    override fun stop() {
        aliveFlag = false
        turnActive = false
        try { approvalExecutor.shutdownNow() } catch (_: Exception) {}
        try { stdin?.close() } catch (_: Exception) {}
        proc?.let { if (it.isAlive) it.destroyForcibly() }
        proc = null
        outQueue.put(STOP)
    }

    override fun recentStderrLines(): List<String> = recentStderr.toList()

    // --- JSON-RPC framing (newline-delimited; single writer serialized on [writeLock]) ---

    private fun sendRequest(method: String, params: JsonObject): Long {
        val id = idCounter.incrementAndGet()
        writeMessage(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        })
        return id
    }

    private fun sendNotification(method: String, params: JsonObject) {
        writeMessage(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
        })
    }

    private fun sendResult(id: com.google.gson.JsonElement, result: JsonObject) {
        writeMessage(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("result", result)
        })
    }

    private fun writeMessage(obj: JsonObject) {
        val line = obj.toString()
        synchronized(writeLock) {
            try {
                val w = stdin ?: return
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (e: Exception) {
                log.warn("codex app-server write failed: ${e.message}")
            }
        }
    }

    /** Surfaces an internal RPC/spawn failure through the parser (→ error Result / AuthFailure). */
    private fun enqueueError(message: String) {
        val obj = JsonObject().apply {
            addProperty("method", "error")
            add("params", JsonObject().apply { addProperty("message", message) })
        }
        outQueue.put(obj.toString())
    }

    private fun errorMessage(error: JsonObject): String =
        error.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "codex app-server error"

    companion object {
        private val STOP = "\u0000__CODEX_APP_SERVER_STOP__"
        private const val MAX_STDERR_LINES = 200
        private const val CLIENT_VERSION = "2.0.0"

        /**
         * Reasoning-summary level requested per thread (`model_reasoning_summary`). Must be a concrete
         * level — "auto" produces no summary deltas over the app-server for the subscription flow.
         */
        private const val REASONING_SUMMARY_LEVEL = "detailed"

        /**
         * Maps ClawDEA's effort dropdown to codex's `effort` value (`minimal|low|medium|high`).
         * `xhigh`/`max` have no codex equivalent — collapse to `high`. A blank/"default" effort omits
         * the override (codex uses the model default).
         */
        internal fun mapEffort(effort: String): String? = when (effort.trim().lowercase()) {
            "minimal" -> "minimal"
            "low" -> "low"
            "medium" -> "medium"
            "high", "xhigh", "max" -> "high"
            else -> null
        }

        /**
         * Extracts the shell command from a `item/commandExecution/requestApproval` request. The
         * app-server approval params are not in the generated schema (unstable), so this probes the
         * likely locations: `params.command`, `params.item.command`, `params.commandExecution.command`.
         */
        internal fun extractCommand(obj: JsonObject): String {
            val p = obj.paramsObj()
            return p.strDeep("command")
                ?: p.objAt("item")?.strDeep("command")
                ?: p.objAt("commandExecution")?.strDeep("command")
                ?: ""
        }

        /** Extracts the item id (used as the permission tool_use_id) from an approval request. */
        internal fun extractItemId(obj: JsonObject): String {
            val p = obj.paramsObj()
            return p.strDeep("itemId")
                ?: p.objAt("item")?.strDeep("id")
                ?: p.strDeep("id")
                ?: ""
        }

        /**
         * Extracts the changed file paths from a `item/fileChange/requestApproval` request, probing
         * `params.changes[].path`, `params.item.changes[].path`, and `params.fileChange.changes[].path`.
         */
        internal fun extractChangePaths(obj: JsonObject): List<String> {
            val p = obj.paramsObj()
            val changes = p.arrAt("changes")
                ?: p.objAt("item")?.arrAt("changes")
                ?: p.objAt("fileChange")?.arrAt("changes")
                ?: return emptyList()
            return changes.mapNotNull { el ->
                (el as? JsonObject)?.strDeep("path")
            }
        }

        private fun JsonObject.paramsObj(): JsonObject =
            get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

        private fun JsonObject.strDeep(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.objAt(key: String): JsonObject? =
            get(key)?.takeIf { it.isJsonObject }?.asJsonObject

        private fun JsonObject.arrAt(key: String): JsonArray? =
            get(key)?.takeIf { it.isJsonArray }?.asJsonArray

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
