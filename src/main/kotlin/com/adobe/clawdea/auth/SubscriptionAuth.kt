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
package com.adobe.clawdea.auth

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Small surface that `SubscriptionAuth` calls to discover status. Kept as an
 * interface so tests can inject fakes without wiring `SubscriptionAuthProbe`.
 */
interface SubscriptionAuthProbeFacade {
    fun probe(): AuthStatus
}

data class ProcessOutcome(
    val exit: Int,
    val stdout: String,
    val stderr: String,
) {
    companion object {
        const val EXIT_TIMEOUT: Int = Int.MIN_VALUE
    }
}

/**
 * Interactive handle passed to streaming callbacks. Lets the callback write
 * to the subprocess's stdin (e.g. for OAuth paste-code prompts) and kill it.
 */
interface ProcessHandle {
    fun writeLine(text: String)
    fun kill()
}

interface ProcessRunner {
    fun run(command: List<String>, timeoutMillis: Long): ProcessOutcome

    /**
     * Runs a command and streams output lines to [onOutput] as they arrive.
     * The callback can write to stdin and kill the process via the handle.
     * Returns when the subprocess exits or the timeout elapses. The default
     * implementation falls back to [run] — streaming is only observable when
     * the concrete runner overrides this.
     */
    fun runStreaming(
        command: List<String>,
        timeoutMillis: Long,
        onOutput: (line: String, handle: ProcessHandle) -> Unit,
    ): ProcessOutcome = run(command, timeoutMillis)
}

internal class DefaultProcessRunner : ProcessRunner {
    override fun run(command: List<String>, timeoutMillis: Long): ProcessOutcome {
        val proc = ProcessBuilder(command).redirectErrorStream(false).start()
        val exited = proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!exited) {
            proc.destroyForcibly()
            return ProcessOutcome(ProcessOutcome.EXIT_TIMEOUT, "", "")
        }
        return ProcessOutcome(
            exit = proc.exitValue(),
            stdout = proc.inputStream.bufferedReader().readText(),
            stderr = proc.errorStream.bufferedReader().readText(),
        )
    }

    override fun runStreaming(
        command: List<String>,
        timeoutMillis: Long,
        onOutput: (line: String, handle: ProcessHandle) -> Unit,
    ): ProcessOutcome {
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val stdinWriter = proc.outputStream.bufferedWriter()
        val handle = object : ProcessHandle {
            override fun writeLine(text: String) {
                try {
                    stdinWriter.write(text)
                    stdinWriter.newLine()
                    stdinWriter.flush()
                } catch (_: Exception) {}
            }
            override fun kill() {
                proc.destroyForcibly()
            }
        }
        val collected = StringBuilder()
        val reader = Thread({
            proc.inputStream.bufferedReader().use { r ->
                r.forEachLine { line ->
                    collected.appendLine(line)
                    try { onOutput(line, handle) } catch (_: Exception) {}
                }
            }
        }, "ClawDEA-auth-login-reader").apply {
            isDaemon = true
            start()
        }
        val exited = proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        try { stdinWriter.close() } catch (_: Exception) {}
        if (!exited) {
            proc.destroyForcibly()
            reader.join(500)
            return ProcessOutcome(ProcessOutcome.EXIT_TIMEOUT, collected.toString(), "")
        }
        reader.join(500)
        return ProcessOutcome(
            exit = proc.exitValue(),
            stdout = collected.toString(),
            stderr = "",
        )
    }
}

@Service
class SubscriptionAuth(
    private val probeFactory: () -> SubscriptionAuthProbeFacade = { defaultProbeFacade() },
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val processRunner: ProcessRunner = DefaultProcessRunner(),
    cliPath: String? = null,
    private val signInTimeoutMillis: Long = 2 * 60 * 1000,
    private val signOutTimeoutMillis: Long = 10_000,
) {
    private val cliPathProvider: () -> String = cliPath?.let { { it } } ?: { defaultCliPath() }
    private val log = Logger.getInstance(SubscriptionAuth::class.java)
    private val lock = ReentrantLock()
    private val listeners = CopyOnWriteArrayList<SubscriptionAuthEventListener>()

    @Volatile private var cached: AuthStatus = AuthStatus.Unknown
    @Volatile private var cachedAt: Long = 0L
    @Volatile private var hasCachedResult: Boolean = false
    @Volatile private var lastSignInError: String? = null

    fun getStatus(): AuthStatus {
        if (isFresh()) return cached
        return if (java.awt.EventQueue.isDispatchThread()) {
            ensureBackgroundRefresh()
            cached
        } else {
            getStatusBlocking()
        }
    }

    fun getStatusBlocking(): AuthStatus {
        if (isFresh()) return cached
        lock.withLock {
            if (isFresh()) return cached
            val fresh = runCatching { probeFactory().probe() }
                .getOrElse {
                    log.warn("subscription probe failed", it)
                    AuthStatus.Unknown
                }
            updateCache(fresh)
            return fresh
        }
    }

    fun invalidateCache() {
        lock.withLock {
            cachedAt = 0L
            hasCachedResult = false
        }
    }

    fun addListener(listener: SubscriptionAuthEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SubscriptionAuthEventListener) {
        listeners.remove(listener)
    }

    fun lastSignInError(): String? = lastSignInError

    fun signIn(onComplete: (AuthStatus) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            signInBlocking(onComplete)
        }
    }

    fun signInBlocking(onComplete: (AuthStatus) -> Unit) {
        lastSignInError = null
        val promptedForCode = java.util.concurrent.atomic.AtomicBoolean(false)
        val outcome = try {
            processRunner.runStreaming(
                command = listOf(cliPathProvider(), "auth", "login", "--claudeai"),
                timeoutMillis = signInTimeoutMillis,
            ) { line, handle ->
                // The CLI opens the browser itself — we only watch for the
                // paste-code prompt (needed when the browser can't redirect).
                if (PASTE_PROMPT_REGEX.containsMatchIn(line) && promptedForCode.compareAndSet(false, true)) {
                    val code = promptForPasteCode()
                    if (code.isNullOrBlank()) {
                        lastSignInError = "sign-in canceled"
                        handle.kill()
                    } else {
                        handle.writeLine(code.trim())
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("auth login subprocess failed", e)
            lastSignInError = e.message ?: "subprocess failed"
            invalidateCache()
            onComplete(getStatusBlocking())
            return
        }
        if (outcome.exit != 0 && lastSignInError == null) {
            lastSignInError = firstNonBlankLine(outcome.stdout, outcome.stderr)
                ?: "sign-in failed (exit ${outcome.exit})"
        }
        invalidateCache()
        onComplete(getStatusBlocking())
    }

    /**
     * Blocks the calling thread until the user submits or cancels the
     * paste-code dialog on the EDT. Returns null on cancel.
     */
    private fun promptForPasteCode(): String? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = java.util.concurrent.atomic.AtomicReference<String?>(null)
        ApplicationManager.getApplication().invokeLater {
            try {
                val input = com.intellij.openapi.ui.Messages.showInputDialog(
                    "Paste the code shown in your browser after signing in:",
                    "Sign in with Claude",
                    com.intellij.openapi.ui.Messages.getQuestionIcon(),
                )
                holder.set(input)
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(5, TimeUnit.MINUTES)) holder.get() else null
    }

    private fun firstNonBlankLine(vararg texts: String): String? {
        for (text in texts) {
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) return trimmed.take(MAX_ERROR_LEN)
            }
        }
        return null
    }

    fun reauthenticate(onComplete: (AuthStatus) -> Unit) = signIn(onComplete)

    fun signOut(onComplete: (AuthStatus) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            signOutBlocking(onComplete)
        }
    }

    fun signOutBlocking(onComplete: (AuthStatus) -> Unit) {
        runAuthCommand(
            command = listOf(cliPathProvider(), "auth", "logout"),
            timeoutMillis = signOutTimeoutMillis,
            onComplete = onComplete,
            storeError = false,
        )
    }

    private fun runAuthCommand(
        command: List<String>,
        timeoutMillis: Long,
        onComplete: (AuthStatus) -> Unit,
        storeError: Boolean,
    ) {
        if (storeError) lastSignInError = null
        val outcome = try {
            processRunner.run(command, timeoutMillis)
        } catch (e: Exception) {
            log.warn("auth subprocess failed", e)
            if (storeError) lastSignInError = e.message ?: "subprocess failed"
            invalidateCache()
            val status = getStatusBlocking()
            onComplete(status)
            return
        }
        if (storeError && outcome.exit != 0) {
            lastSignInError = outcome.stderr.lines().firstOrNull { it.isNotBlank() }?.take(MAX_ERROR_LEN)
                ?: "sign-in failed (exit ${outcome.exit})"
        }
        invalidateCache()
        val status = getStatusBlocking()
        onComplete(status)
    }

    private fun isFresh(): Boolean =
        hasCachedResult && (System.currentTimeMillis() - cachedAt) < cacheTtlMillis

    private fun updateCache(status: AuthStatus) {
        val previous = if (hasCachedResult) cached else null
        cached = status
        cachedAt = System.currentTimeMillis()
        hasCachedResult = true
        if (previous != status) {
            listeners.forEach {
                runCatching { it.onStatusChanged(status) }
                    .onFailure { log.warn("listener failed", it) }
            }
            // Also notify via the application message bus so subscribers that
            // hook in through SubscriptionAuthEventListener.TOPIC (e.g. the
            // settings card) receive transitions. Guarded so tests without a
            // running application don't blow up.
            runCatching {
                ApplicationManager.getApplication()
                    ?.messageBus
                    ?.syncPublisher(SubscriptionAuthEventListener.TOPIC)
                    ?.onStatusChanged(status)
            }.onFailure { log.warn("bus publish failed", it) }
        }
    }

    private fun ensureBackgroundRefresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            getStatusBlocking()
        }
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MILLIS: Long = 30_000
        private const val MAX_ERROR_LEN = 200

        // The CLI prints something like "Paste code here if prompted" on the
        // line immediately before reading stdin for the OAuth code.
        private val PASTE_PROMPT_REGEX = Regex("""(?i)paste\s+(the\s+)?code""")

        fun getInstance(): SubscriptionAuth =
            ApplicationManager.getApplication().getService(SubscriptionAuth::class.java)

        private fun defaultCliPath(): String =
            com.adobe.clawdea.cli.resolveClaudeCliPath(ClawDEASettings.getInstance().state.cliPath)

        private fun defaultProbeFacade(): SubscriptionAuthProbeFacade {
            val probe = SubscriptionAuthProbe(cliPath = defaultCliPath())
            return object : SubscriptionAuthProbeFacade {
                override fun probe() = probe.probe()
            }
        }
    }
}
