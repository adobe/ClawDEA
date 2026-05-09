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

import com.adobe.clawdea.knowledge.wiki.WikiLink
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DriftAutoApplier {

    private val LOG = Logger.getInstance(DriftAutoApplier::class.java)

    /**
     * Apply high-confidence fixes for the given events. Returns the events
     * that were actually applied (so the orchestrator can dismiss them).
     * Events without a high-confidence fix are skipped (returned in the
     * unapplied set, to be surfaced via the banner).
     */
    fun apply(events: List<DriftEvent>, today: String = todayIso()): List<DriftEvent> {
        val applied = mutableListOf<DriftEvent>()
        for (event in events) {
            val ok = when (event) {
                is DriftEvent.CodeRename -> applyCodeRename(event)
                is DriftEvent.ManifestStale -> applyManifestStale(event, today)
                is DriftEvent.DreamLinkNormalization -> applyDreamLinkNormalization(event)
                is DriftEvent.DreamIndexCleanup,
                is DriftEvent.DreamSourceReferenceFix,
                is DriftEvent.DreamDuplicateConcept,
                is DriftEvent.DreamStaleConcept,
                is DriftEvent.DreamMissingConcept,
                -> false
                else -> false
            }
            if (ok) applied += event
        }
        return applied
    }

    private fun applyCodeRename(event: DriftEvent.CodeRename): Boolean {
        val replacement = event.suggestedReplacement ?: return false
        val text = runCatching { Files.readString(event.wikiPage) }.getOrNull() ?: return false
        if (!text.contains(event.brokenLink)) return false  // already-applied or out-of-date event
        val updated = text.replaceFirst("(${event.brokenLink})", "($replacement)")
        if (updated == text) return false  // no-op: brokenLink isn't in markdown-link parens, skip
        return atomicWrite(event.wikiPage, updated)
    }

    private fun applyManifestStale(event: DriftEvent.ManifestStale, today: String): Boolean {
        val text = runCatching { Files.readString(event.manifestPath) }.getOrNull() ?: return false
        val lines = text.lines().toMutableList()
        val idx = event.lineHint - 1
        if (idx < 0 || idx >= lines.size) return false
        val line = lines[idx]
        // Defensive: only comment out if the line still looks like a bullet for this key.
        if (!line.contains("**${event.repoKey}**")) return false
        lines[idx] = "# $line  # auto-removed $today: path missing"
        return atomicWrite(event.manifestPath, lines.joinToString("\n"))
    }

    private fun applyDreamLinkNormalization(event: DriftEvent.DreamLinkNormalization): Boolean {
        if (!event.autoApplicable) return false
        val text = runCatching { Files.readString(event.targetFile) }.getOrNull() ?: return false
        val pageRelativePath = relativizeWikiPage(event.targetFile)
        val oldLinks = WikiLink.extractConceptLinks(pageRelativePath, text)
            .filter { it.original.startsWith("[[") }
        if (oldLinks.size != 1) return false

        val oldLink = oldLinks.single()
        val replacement = WikiLink.toMarkdownLink(pageRelativePath, oldLink.targetSlug)
        val updated = text.replace(oldLink.original, replacement)
        if (updated == text) return false
        return atomicWrite(event.targetFile, updated)
    }

    private fun relativizeWikiPage(targetFile: Path): String {
        val normalized = targetFile.normalize()
        val names = (0 until normalized.nameCount).map { normalized.getName(it).toString() }
        val wikiIndex = names.windowed(size = 2).indexOfFirst { it == listOf(".claude", "wiki") }
        return if (wikiIndex >= 0) {
            names.drop(wikiIndex + 2).joinToString("/")
        } else {
            normalized.fileName.toString()
        }
    }

    private fun atomicWrite(target: Path, content: String): Boolean {
        return try {
            val parent = target.parent
            val temp = Files.createTempFile(parent, target.fileName.toString() + ".tmp", "")
            try {
                Files.writeString(temp, content)
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
                true
            } finally {
                if (Files.exists(temp)) {
                    try { Files.deleteIfExists(temp) } catch (_: Exception) {}
                }
            }
        } catch (e: Throwable) {
            LOG.warn("DriftAutoApplier failed to write $target: ${e.message}")
            false
        }
    }

    internal fun todayIso(): String =
        LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
}
