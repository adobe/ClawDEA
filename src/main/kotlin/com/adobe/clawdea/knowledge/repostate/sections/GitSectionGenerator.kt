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
package com.adobe.clawdea.knowledge.repostate.sections

import com.adobe.clawdea.knowledge.repostate.RepoStateSection
import com.adobe.clawdea.knowledge.repostate.RepoStateSectionGenerator
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class GitSectionGenerator : RepoStateSectionGenerator {

    override val name = "git"

    override fun generate(project: Project): RepoStateSection? {
        val basePath = project.basePath ?: return null
        val gitDir = File(basePath, ".git")
        if (!gitDir.exists()) return null

        val branch = runGit(basePath, listOf("rev-parse", "--abbrev-ref", "HEAD"))?.trim().orEmpty()
        if (branch.isBlank()) return null

        val log = runGit(
            basePath,
            listOf("log", "--pretty=format:COMMIT|%H|%s", "--name-only", "-n", "50", "--since=7.days"),
        ).orEmpty()

        val parsed = parseLog(log)
        val body = formatBody(branch = branch, parsed = parsed)
        return RepoStateSection(heading = "Current focus (last 7 days)", body = body)
    }

    private fun runGit(workingDir: String, args: List<String>): String? {
        return try {
            val cmd = mutableListOf("git").also { it.addAll(args) }
            val process = ProcessBuilder(cmd)
                .directory(File(workingDir))
                .redirectErrorStream(false)
                .start()
            // Drain stdout on a separate thread so the waitFor timeout below
            // actually fires. If we read inline, readText() blocks until EOF
            // and the 2-second timeout is unreachable on a stuck process.
            val output = AtomicReference<String?>()
            val drainer = Thread({
                try {
                    output.set(process.inputStream.bufferedReader().readText())
                } catch (_: Exception) {
                    // Process killed or stream closed mid-read; output stays null.
                }
            }, "ClawDEA-git-drain").apply {
                isDaemon = true
                start()
            }
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                drainer.interrupt()
                LOG.warn("git ${args.joinToString(" ")} timed out")
                return null
            }
            // Process exited; give the drainer a moment to flush whatever the
            // process wrote before returning. 500ms is plenty for a finished
            // process — anything larger means the kernel buffer is huge,
            // which we'd cap anyway.
            drainer.join(500)
            output.get()
        } catch (e: Exception) {
            LOG.warn("git ${args.joinToString(" ")} failed: ${e.message}")
            null
        }
    }

    data class Commit(val sha: String, val subject: String)
    data class HotFile(val path: String, val editCount: Int)
    data class ParsedLog(val commits: List<Commit>, val hotFiles: List<HotFile>)

    companion object {
        private val LOG = Logger.getInstance(GitSectionGenerator::class.java)
        private const val MAX_COMMITS_SHOWN = 5
        private const val MAX_HOT_FILES_SHOWN = 10

        fun parseLog(text: String): ParsedLog {
            val commits = mutableListOf<Commit>()
            val editCount = LinkedHashMap<String, Int>()
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("COMMIT|")) {
                    val parts = trimmed.removePrefix("COMMIT|").split("|", limit = 2)
                    if (parts.size == 2) commits.add(Commit(parts[0], parts[1]))
                } else if (trimmed.contains('/') || trimmed.contains('.')) {
                    editCount[trimmed] = (editCount[trimmed] ?: 0) + 1
                }
            }
            val hotFiles = editCount.entries
                .map { HotFile(it.key, it.value) }
                .sortedByDescending { it.editCount }
            return ParsedLog(commits = commits, hotFiles = hotFiles)
        }

        fun formatBody(branch: String, parsed: ParsedLog): String {
            val sb = StringBuilder()
            sb.appendLine("Branch: $branch")
            if (parsed.commits.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Recent commits (last 7 days):")
                for (commit in parsed.commits.take(MAX_COMMITS_SHOWN)) {
                    sb.appendLine("  ${commit.sha} ${commit.subject}")
                }
            }
            if (parsed.hotFiles.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Hot files (rank by edit count, last 7 days):")
                for (hot in parsed.hotFiles.take(MAX_HOT_FILES_SHOWN)) {
                    sb.appendLine("  ${hot.path} (${hot.editCount} edits)")
                }
            }
            return sb.toString()
        }
    }
}
