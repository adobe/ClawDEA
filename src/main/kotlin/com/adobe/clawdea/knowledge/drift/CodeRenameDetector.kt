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

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Walks a wiki directory, parses standard markdown links of the form `[label](path)`,
 * and emits a [DriftEvent.CodeRename] for each link whose target does not resolve to
 * a regular file. When the basename matches exactly one `.kt`/`.java` file across the
 * given source roots, includes a [DriftEvent.CodeRename.suggestedReplacement].
 *
 * Skips:
 *  - Anchor-only links (`#section`)
 *  - Schemed URLs (`https:`, `mailto:`, etc.)
 *  - Wikilink syntax (`[[concept]]`) — naturally not matched by the standard regex.
 */
object CodeRenameDetector {

    private val LOG = Logger.getInstance(CodeRenameDetector::class.java)
    private val LINK_RX = Regex("""\[([^\]]*)\]\(([^)]+)\)""")
    private val SCHEMED_RX = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*:""")

    fun detect(wikiDir: Path, sourceRoots: List<Path>): List<DriftEvent> {
        if (!Files.isDirectory(wikiDir)) return emptyList()
        val out = mutableListOf<DriftEvent>()
        try {
            Files.walk(wikiDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName?.toString()?.endsWith(".md") == true }
                    .forEach { page -> scanPage(page, sourceRoots, out) }
            }
        } catch (e: Throwable) {
            LOG.warn("CodeRenameDetector walk failed: ${e.message}")
        }
        return out
    }

    private fun scanPage(page: Path, sourceRoots: List<Path>, out: MutableList<DriftEvent>) {
        val text = runCatching { Files.readString(page) }.getOrNull() ?: return
        for (match in LINK_RX.findAll(text)) {
            val rawTarget = match.groupValues[2].trim()
            // Skip anchor-only and schemed URLs. Wikilinks use [[...]], which the LINK_RX won't match.
            if (rawTarget.startsWith("#")) continue
            if (SCHEMED_RX.containsMatchIn(rawTarget)) continue

            // Strip ":line" or ":line-line" suffix for resolution.
            val resolvedTarget = stripLineSuffix(rawTarget)
            val resolved = page.parent.resolve(resolvedTarget).normalize()
            if (Files.isRegularFile(resolved)) continue

            // Broken link.
            val basename = resolved.fileName?.toString() ?: continue
            val suggestion = findUniqueBasenameMatch(basename, sourceRoots, page)
            out += DriftEvent.CodeRename(
                wikiPage = page,
                brokenLink = rawTarget,
                suggestedReplacement = suggestion,
            )
        }
    }

    internal fun stripLineSuffix(target: String): String {
        // Strip trailing ":42" or ":42-50" only if it follows a path component.
        val idx = target.lastIndexOf(':')
        if (idx <= 0) return target
        val tail = target.substring(idx + 1)
        if (tail.matches(Regex("""\d+(-\d+)?"""))) return target.substring(0, idx)
        return target
    }

    private fun findUniqueBasenameMatch(basename: String, sourceRoots: List<Path>, fromPage: Path): String? {
        val matches = mutableListOf<Path>()
        for (root in sourceRoots) {
            if (!Files.isDirectory(root)) continue
            try {
                Files.walk(root, 8).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.fileName?.toString() == basename }
                        .forEach { matches.add(it) }
                }
            } catch (_: Throwable) { /* skip bad subtree */ }
        }
        if (matches.size != 1) return null
        // Build a path relative to the wiki page's parent directory.
        return runCatching {
            fromPage.parent.relativize(matches.single()).toString().replace('\\', '/')
        }.getOrNull()
    }
}
