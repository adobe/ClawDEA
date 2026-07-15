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

import com.adobe.clawdea.CLAUDE_DIR
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Resolves the full environment needed by the Claude CLI subprocess.
 *
 * When IntelliJ is launched from Finder/Dock, the process environment lacks
 * shell-initialized vars (AWS creds, Bedrock config, etc.). This resolver
 * sources the user's configured env script (e.g., ~/claude.sh) inside a
 * login shell and captures the resulting environment.
 *
 * Resolution order (later wins):
 * 1. Login shell environment (.zshrc, .bashrc, etc.)
 * 2. User-configured env script (Settings → CLI env script)
 * 3. ~/.claude/plugin-env file (manual override)
 */
object CliEnvironment {

    private val log = Logger.getInstance(CliEnvironment::class.java)

    private val ENV_FILE = File(System.getProperty("user.home"), "$CLAUDE_DIR/plugin-env")

    @Volatile
    private var cachedShellEnv: Map<String, String>? = null

    @Volatile
    private var cacheTimestamp: Long = 0

    @Volatile
    private var cachedScript: String? = null

    /** Background capture in progress (if any). */
    @Volatile
    private var pendingCapture: CompletableFuture<Map<String, String>>? = null

    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    /**
     * Merge shell environment + env script + plugin-env file into the subprocess env map.
     */
    fun applyTo(env: MutableMap<String, String>) {
        val shellEnv = getShellEnvironment()
        for ((k, v) in shellEnv) {
            env.putIfAbsent(k, v)
        }

        // Plugin env file — manual override, highest priority
        val fileEnv = readEnvFile()
        if (fileEnv.isNotEmpty()) {
            log.info("Loaded ${fileEnv.size} env vars from ${ENV_FILE.path}")
            env.putAll(fileEnv)
        }
    }

    /**
     * Pre-warm the shell environment cache on a background thread.
     * Safe to call from any thread — always returns immediately.
     */
    fun preWarm() {
        ensureCaptureStarted()
    }

    /**
     * Start a background shell env capture if the cache is stale and no capture
     * is already in progress. Returns the pending future, or null if the cache
     * is still fresh.
     */
    private fun ensureCaptureStarted(): CompletableFuture<Map<String, String>>? {
        val script = ClawDEASettings.getInstance().state.cliEnvScript
        val now = System.currentTimeMillis()

        if (cachedShellEnv != null && (now - cacheTimestamp) < CACHE_TTL_MS && cachedScript == script) {
            return null
        }

        val existing = pendingCapture
        if (existing != null && !existing.isDone) {
            return existing
        }

        val future = CompletableFuture.supplyAsync {
            captureShellEnv(script)
        }
        pendingCapture = future

        future.thenAccept { result ->
            cachedShellEnv = result
            cacheTimestamp = System.currentTimeMillis()
            cachedScript = script
            pendingCapture = null
        }

        return future
    }

    /**
     * Return cached shell env if fresh, otherwise kick off a background capture.
     *
     * If the cache is stale and we're on the EDT, this returns the stale (or empty)
     * cache immediately and refreshes in the background. Off the EDT the caller
     * blocks briefly (≤ 5 s) for a fresh result.
     *
     * The shell env is an *enhancement* (extra PATH / cloud creds for Finder/Dock
     * launches), never a hard requirement — so a slow login shell must never abort
     * the CLI launch. A timeout (or any capture failure) degrades to the last-known
     * cache, or an empty map, and lets the (still-running) background capture warm
     * the cache for next time. Previously the raw `future.get(5s)` let a
     * TimeoutException bubble out of CliProcess.start → CliBridge.start, hard-failing
     * every off-EDT (re)start/resume when the user's `.zshrc` was slow.
     */
    private fun getShellEnvironment(): Map<String, String> {
        val future = ensureCaptureStarted()
            ?: return cachedShellEnv ?: emptyMap()

        if (java.awt.EventQueue.isDispatchThread()) {
            return cachedShellEnv ?: emptyMap()
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            log.warn("Shell env capture slow (>5s); using ${cachedShellEnv?.size ?: 0} cached vars, refresh continues in background")
            cachedShellEnv ?: emptyMap()
        } catch (e: Exception) {
            log.warn("Shell env capture failed; falling back to cached/empty env", e)
            cachedShellEnv ?: emptyMap()
        }
    }

    private fun captureShellEnv(envScript: String): Map<String, String> {
        // Windows has no POSIX login shell to source, and the JVM already inherits the full
        // user environment (the Finder/Dock PATH-stripping problem this solves is macOS-only).
        // Running `/bin/zsh -l -c` here would always fail with CreateProcess error=2.
        if (System.getProperty("os.name").orEmpty().lowercase().contains("windows")) {
            return emptyMap()
        }
        val shell = System.getenv("SHELL") ?: "/bin/zsh"

        // Build the command: source the shell rc file + env script, then print env.
        // We use -l (login) but NOT -i (interactive) to avoid loading completions
        // and prompt themes. The rc file is sourced explicitly to pick up env vars.
        val home = System.getProperty("user.home")
        val rcFile = if (shell.endsWith("/bash")) "$home/.bashrc" else "$home/.zshrc"

        val cmd = buildString {
            append("[ -f '$rcFile' ] && source '$rcFile' 2>/dev/null; ")
            if (envScript.isNotBlank()) {
                val expanded = envScript.replace("~", home)
                append("source '")
                append(expanded)
                append("' 2>/dev/null; ")
            }
            append("env")
        }

        try {
            val pb = ProcessBuilder(shell, "-l", "-c", cmd)
                .redirectErrorStream(true)

            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exited = proc.waitFor(10, TimeUnit.SECONDS)

            if (!exited) {
                proc.destroyForcibly()
                log.warn("Shell env capture timed out")
                return emptyMap()
            }

            val env = mutableMapOf<String, String>()
            for (line in output.lines()) {
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val key = line.substring(0, idx)
                    if (key.all { it.isLetterOrDigit() || it == '_' }) {
                        env[key] = line.substring(idx + 1)
                    }
                }
            }

            log.info("Captured ${env.size} env vars from $shell" +
                if (envScript.isNotBlank()) " (sourced $envScript)" else "")
            return env
        } catch (e: Exception) {
            log.warn("Failed to capture shell environment", e)
            return emptyMap()
        }
    }

    /**
     * Resolve [binary] to an absolute path using the user's login-shell `PATH` (which the JVM
     * lacks on Finder/Dock launches). Returns null when the binary isn't on the shell PATH or the
     * shell env isn't available yet (e.g. cold cache on the EDT). Off the EDT this may block briefly
     * while the shell env is captured — acceptable on the CLI-spawn path, which already depends on it.
     */
    fun resolveOnShellPath(binary: String): String? {
        val path = getShellEnvironment()["PATH"]?.takeIf { it.isNotBlank() } ?: return null
        return findExecutableOnUnixPath(binary, path)
    }

    /** Force-refresh the cached environment on next access. */
    fun invalidateCache() {
        cachedShellEnv = null
        cacheTimestamp = 0
        pendingCapture = null
    }

    private fun readEnvFile(): Map<String, String> {
        if (!ENV_FILE.isFile || !ENV_FILE.canRead()) return emptyMap()

        return try {
            ENV_FILE.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .associate { line ->
                    val idx = line.indexOf('=')
                    line.substring(0, idx) to line.substring(idx + 1)
                }
        } catch (e: Exception) {
            log.warn("Failed to read plugin env file: ${ENV_FILE.path}", e)
            emptyMap()
        }
    }
}
