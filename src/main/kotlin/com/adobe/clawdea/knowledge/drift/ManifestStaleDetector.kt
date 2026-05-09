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
package com.adobe.clawdea.knowledge.drift

import com.adobe.clawdea.knowledge.workspace.WorkspaceManifestParser
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

object ManifestStaleDetector {

    private val LOG = Logger.getInstance(ManifestStaleDetector::class.java)
    private val BULLET_KEY_RX = Regex("""^-\s+\*\*([a-z0-9-]+)\*\*""")

    fun detect(manifestPath: Path): List<DriftEvent> {
        if (!Files.isRegularFile(manifestPath)) return emptyList()
        val text = runCatching { Files.readString(manifestPath) }.getOrNull() ?: return emptyList()
        val manifest = WorkspaceManifestParser.parse(text, discoveredAt = manifestPath.parent)
        val out = mutableListOf<DriftEvent>()
        val lines = text.lines()
        for (group in manifest.groups) {
            for (repo in group.repos) {
                val resolved = runCatching { repo.resolvedPath(manifestPath.parent) }.getOrNull() ?: continue
                if (Files.exists(resolved)) continue
                val lineHint = findBulletLine(lines, repo.key)
                out += DriftEvent.ManifestStale(
                    repoKey = repo.key,
                    groupName = group.name,
                    manifestPath = manifestPath,
                    lineHint = lineHint,
                )
            }
        }
        return out
    }

    /** 1-based line number of the bullet whose key matches; -1 if not found. */
    private fun findBulletLine(lines: List<String>, repoKey: String): Int {
        for ((idx, line) in lines.withIndex()) {
            val match = BULLET_KEY_RX.find(line.trim()) ?: continue
            if (match.groupValues[1] == repoKey) return idx + 1
        }
        LOG.warn("ManifestStaleDetector could not find bullet line for key '$repoKey'")
        return -1
    }
}
