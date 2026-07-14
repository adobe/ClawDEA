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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * OpenAI ChatGPT (codex) subscription auth service — the codex peer of [SubscriptionAuth].
 *
 * Drives the `codex` CLI: `signIn` streams `codex login` (codex opens the browser / device-auth
 * itself — unlike Claude there is no stdin paste-code step), `signOut` runs `codex logout`, and
 * status is read via [CodexSubscriptionAuthProbe] (`codex login status`). Transitions are published
 * on the DISTINCT [CodexSubscriptionAuthEventListener.TOPIC] so the Claude subscription card is not
 * affected. Reuses [ProcessRunner]/[ProcessOutcome]/[SubscriptionAuthProbeFacade] from
 * [SubscriptionAuth]; both are injectable for tests.
 */
@Service
class CodexSubscriptionAuth(
    private val probeFactory: () -> SubscriptionAuthProbeFacade = { defaultProbeFacade() },
    private val cacheTtlMillis: Long = SubscriptionAuth.DEFAULT_CACHE_TTL_MILLIS,
    private val processRunner: ProcessRunner = DefaultProcessRunner(),
    cliPath: String? = null,
    private val signInTimeoutMillis: Long = 5 * 60 * 1000,
    private val signOutTimeoutMillis: Long = 10_000,
) {
    private val cliPathProvider: () -> String = cliPath?.let { { it } } ?: { defaultCliPath() }
    private val log = Logger.getInstance(CodexSubscriptionAuth::class.java)
    private val lock = ReentrantLock()
    private val listeners = CopyOnWriteArrayList<CodexSubscriptionAuthEventListener>()

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
                    log.warn("codex subscription probe failed", it)
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

    fun addListener(listener: CodexSubscriptionAuthEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CodexSubscriptionAuthEventListener) {
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
        val outcome = try {
            // codex opens the browser / prints a device-auth URL itself; there is no
            // stdin paste-code step, so we stream only to keep the subprocess draining.
            processRunner.runStreaming(
                command = listOf(cliPathProvider(), "login"),
                timeoutMillis = signInTimeoutMillis,
            ) { _, _ -> }
        } catch (e: Exception) {
            log.warn("codex login subprocess failed", e)
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

    fun reauthenticate(onComplete: (AuthStatus) -> Unit) = signIn(onComplete)

    fun signOut(onComplete: (AuthStatus) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            signOutBlocking(onComplete)
        }
    }

    fun signOutBlocking(onComplete: (AuthStatus) -> Unit) {
        val outcome = try {
            processRunner.run(listOf(cliPathProvider(), "logout"), signOutTimeoutMillis)
        } catch (e: Exception) {
            log.warn("codex logout subprocess failed", e)
            invalidateCache()
            onComplete(getStatusBlocking())
            return
        }
        if (outcome.exit != 0) {
            log.info("codex logout exited ${outcome.exit}")
        }
        invalidateCache()
        onComplete(getStatusBlocking())
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
            runCatching {
                ApplicationManager.getApplication()
                    ?.messageBus
                    ?.syncPublisher(CodexSubscriptionAuthEventListener.TOPIC)
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
        private const val MAX_ERROR_LEN = 200

        fun getInstance(): CodexSubscriptionAuth =
            ApplicationManager.getApplication().getService(CodexSubscriptionAuth::class.java)

        private fun defaultCliPath(): String =
            com.adobe.clawdea.cli.resolveCodexCliPath(ClawDEASettings.getInstance().state.codexCliPath)

        private fun defaultProbeFacade(): SubscriptionAuthProbeFacade {
            val probe = CodexSubscriptionAuthProbe(cliPath = defaultCliPath())
            return object : SubscriptionAuthProbeFacade {
                override fun probe() = probe.probe()
            }
        }
    }
}
