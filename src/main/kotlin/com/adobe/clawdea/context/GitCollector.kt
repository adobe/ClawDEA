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
// src/main/kotlin/com/adobe/clawdea/context/GitCollector.kt
package com.adobe.clawdea.context

import com.intellij.openapi.project.Project
import git4idea.GitUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler

/**
 * Collects git context: uncommitted changes and recent commits.
 * Uses Git4Idea (IntelliJ's built-in git integration).
 */
class GitCollector {

    fun collect(project: Project): List<ContextItem> {
        val items = mutableListOf<ContextItem>()
        val roots = GitUtil.getRepositories(project)
        if (roots.isEmpty()) return items

        val repo = roots.first()
        val root = repo.root

        // Uncommitted changes (git diff + git diff --cached)
        try {
            val diffHandler = GitLineHandler(project, root, GitCommand.DIFF)
            val diffResult = Git.getInstance().runCommand(diffHandler)
            if (diffResult.success()) {
                items.addAll(parseDiffOutput(diffResult.outputAsJoinedString))
            }

            val cachedHandler = GitLineHandler(project, root, GitCommand.DIFF)
            cachedHandler.addParameters("--cached")
            val cachedResult = Git.getInstance().runCommand(cachedHandler)
            if (cachedResult.success() && cachedResult.outputAsJoinedString.isNotBlank()) {
                val staged = parseDiffOutput(cachedResult.outputAsJoinedString)
                for (item in staged) {
                    items.add(item.copy(label = "Staged changes", score = 0.65))
                }
            }
        } catch (_: Exception) {
            // Git not available or not a git repo — skip silently
        }

        // Recent commits
        try {
            val logHandler = GitLineHandler(project, root, GitCommand.LOG)
            logHandler.addParameters("--oneline", "-10")
            val logResult = Git.getInstance().runCommand(logHandler)
            if (logResult.success()) {
                items.addAll(parseLogOutput(logResult.outputAsJoinedString))
            }
        } catch (_: Exception) {
            // Skip silently
        }

        return items
    }

    companion object {
        fun parseDiffOutput(diff: String): List<ContextItem> {
            if (diff.isBlank()) return emptyList()
            return listOf(ContextItem(
                label = "Uncommitted changes",
                content = diff,
                score = 0.7,
                source = "git"
            ))
        }

        fun parseLogOutput(log: String): List<ContextItem> {
            if (log.isBlank()) return emptyList()
            return listOf(ContextItem(
                label = "Recent commits",
                content = log,
                score = 0.4,
                source = "git"
            ))
        }
    }
}
