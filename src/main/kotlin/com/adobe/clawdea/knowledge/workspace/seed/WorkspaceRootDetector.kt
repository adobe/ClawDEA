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
package com.adobe.clawdea.knowledge.workspace.seed

import java.nio.file.Files
import java.nio.file.Path

/**
 * Walk-up from a project's basePath looking for "workspace-y" parents.
 * A directory qualifies when it has at least MIN_SIBLINGS direct children
 * that are git repos. Blocklisted paths never qualify.
 * Returns deepest-first.
 */
object WorkspaceRootDetector {
    const val MIN_SIBLINGS = 2

    val DEFAULT_BLOCKLIST: Set<String> = setOf(
        System.getProperty("user.home"), "/", "/Users", "/Volumes", "/home",
    )

    fun detect(start: Path, blocklist: Set<String> = DEFAULT_BLOCKLIST): List<Path> {
        val result = mutableListOf<Path>()
        var dir: Path? = start.toAbsolutePath().normalize().parent
        while (dir != null) {
            if (dir.toString() !in blocklist && countGitSiblings(dir) >= MIN_SIBLINGS) result.add(dir)
            dir = dir.parent
        }
        return result
    }

    private fun countGitSiblings(dir: Path): Int {
        if (!Files.isDirectory(dir)) return 0
        return try {
            Files.list(dir).use { stream ->
                stream.filter { child -> Files.isDirectory(child.resolve(".git")) }.count().toInt()
            }
        } catch (_: SecurityException) { 0 } catch (_: java.io.IOException) { 0 }
    }
}
