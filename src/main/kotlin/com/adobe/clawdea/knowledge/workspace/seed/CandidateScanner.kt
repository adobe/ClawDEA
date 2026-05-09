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

object CandidateScanner {
    /**
     * Merge IntelliJ-known projects + filesystem siblings under workspaceRoot.
     * Order: open → filesystem → recent. Dedupe by realpath; require .git/.
     */
    fun scan(
        workspaceRoot: Path,
        openProjects: List<Path>,
        recentProjects: List<Path>,
    ): List<Path> {
        val rootReal = workspaceRoot.toRealPath()
        val ordered = LinkedHashSet<Path>()

        fun addIfUnderRoot(p: Path) {
            val real = runCatching { p.toRealPath() }.getOrNull() ?: return
            if (!real.startsWith(rootReal) || real == rootReal) return
            if (!Files.isDirectory(real.resolve(".git"))) return
            ordered.add(real)
        }

        for (p in openProjects) addIfUnderRoot(p)
        if (Files.isDirectory(workspaceRoot)) {
            try {
                Files.list(workspaceRoot).use { it.forEach { p -> addIfUnderRoot(p) } }
            } catch (_: java.io.IOException) { /* best-effort */ }
        }
        for (p in recentProjects) addIfUnderRoot(p)
        return ordered.toList()
    }
}
