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
package com.adobe.clawdea.chat

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Caches the repo-relative paths of recently modified files for `@`-mention autocomplete.
 *
 * [relativePaths] never blocks: it returns the current snapshot and, when the snapshot is older
 * than [TTL_MS], schedules an off-EDT refresh whose result the *next* call sees. The autocomplete
 * path runs on the EDT on every keystroke, so forking `git log` there froze the UI for hundreds of
 * milliseconds per character on a large repo.
 */
@Service(Service.Level.PROJECT)
class RecentFilesCache(private val project: Project, private val scope: CoroutineScope) {

    private val snapshot = AtomicReference<List<String>>(emptyList())
    private val lastRefreshAtMs = AtomicLong(0L)
    private val refreshing = AtomicBoolean(false)

    /** Last known recently-modified paths, newest first. Never blocks; may be empty on first call. */
    fun relativePaths(): List<String> {
        if (isStale(lastRefreshAtMs.get(), System.currentTimeMillis(), TTL_MS)) {
            scheduleRefresh()
        }
        return snapshot.get()
    }

    private fun scheduleRefresh() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val basePath = project.basePath ?: return@launch
                val paths = readGitLog(basePath)
                if (paths != null) {
                    snapshot.set(paths)
                    lastRefreshAtMs.set(System.currentTimeMillis())
                }
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun readGitLog(basePath: String): List<String>? {
        return try {
            val process = ProcessBuilder(
                "git", "log", "--diff-filter=M", "--name-only", "--pretty=format:", "-50",
            ).directory(java.io.File(basePath)).redirectErrorStream(true).start()

            val captured = AtomicReference<String?>(null)
            val drainer = Thread({
                try {
                    captured.set(process.inputStream.bufferedReader().readText())
                } catch (_: Exception) {
                    // Interrupted on timeout, or the stream closed: leave it null.
                }
            }, "ClawDEA-mention-git-drain").apply { isDaemon = true; start() }

            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                drainer.interrupt()
                LOG.info("git log for @-mention recents timed out")
                return null
            }
            drainer.join(500)
            parseGitLog(captured.get().orEmpty(), MAX_PATHS)
        } catch (e: Exception) {
            LOG.info("git log for @-mention recents failed: ${e.message}")
            null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(RecentFilesCache::class.java)

        const val TTL_MS: Long = 30_000L
        const val MAX_PATHS: Int = 60

        fun getInstance(project: Project): RecentFilesCache =
            project.getService(RecentFilesCache::class.java)

        /**
         * Whether the snapshot needs a refresh. A [nowMs] behind [lastRefreshAtMs] (clock moved
         * backwards) counts as stale so the cache can never wedge.
         */
        internal fun isStale(lastRefreshAtMs: Long, nowMs: Long, ttlMs: Long): Boolean =
            lastRefreshAtMs == 0L || nowMs < lastRefreshAtMs || nowMs - lastRefreshAtMs >= ttlMs

        /** `git log --name-only --pretty=format:` output → distinct paths, first-seen order. */
        internal fun parseGitLog(output: String, max: Int): List<String> =
            output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(max)
                .toList()
    }
}
