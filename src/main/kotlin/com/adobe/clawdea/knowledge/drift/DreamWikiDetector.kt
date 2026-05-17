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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.time.Instant

data class DreamWikiSettings(
    val enabled: Boolean,
    val minElapsedHours: Int = 24,
    val minSignalUnits: Int = 5,
    val scanThrottleMinutes: Int = 10,
)

data class DreamDetectionResult(
    val events: List<DriftEvent>,
    val status: String,
    val filteredCandidateCount: Int,
    val attempted: Boolean = false,
    val successful: Boolean = false,
)

class DreamWikiDetector(
    private val invocation: DreamInvocation = ClaudeDreamInvocation(),
) {

    fun detect(
        projectRoot: Path,
        state: DriftState,
        settings: DreamWikiSettings,
        now: Instant,
        force: Boolean,
        activeTurn: Boolean,
    ): DreamDetectionResult {
        // Dream wiki maintenance is being removed (Task 12 of the wiki maintenance
        // redesign). The body that referenced state.dream* fields has been stripped;
        // this stub remains only to keep callers compiling until the file is deleted.
        return DreamDetectionResult(
            events = emptyList(),
            status = "not-run:dream-disabled",
            filteredCandidateCount = 0,
            attempted = false,
            successful = false,
        )
    }

    private fun validateScoreAndMap(projectRoot: Path, json: String): DreamDetectionResult {
        val validation = DreamOutputValidator.validate(json)
        if (validation.errors.isNotEmpty()) {
            return DreamDetectionResult(
                events = emptyList(),
                status = "invalid:${validation.errors.joinToString("; ")}",
                filteredCandidateCount = 0,
                attempted = true,
                successful = false,
            )
        }

        val scored = DreamCandidateScorer.filterAndRank(
            validation.candidates,
            indexOverContextCap = isIndexOverContextCap(projectRoot.resolve(".claude/wiki/index.md")),
        )
        return DreamDetectionResult(
            events = scored.map { DreamEventMapper.toEvent(projectRoot, it) },
            status = "ok",
            filteredCandidateCount = validation.candidates.size - scored.size,
            attempted = true,
            successful = true,
        )
    }

    private fun buildPrompt(projectRoot: Path): String {
        val indexPath = projectRoot.resolve(".claude/wiki/index.md")
        val index = readCappedText(indexPath, INDEX_CHAR_CAP)
        val wikiContext = buildWikiPageContext(projectRoot.resolve(".claude/wiki"))
        val repoState = readCappedText(projectRoot.resolve(".claude/REPO_STATE.md"), REPO_STATE_CHAR_CAP)
        val notes = readCappedText(projectRoot.resolve(".claude/notes/CURRENT.md"), NOTES_CHAR_CAP)
        val links = collectWikiLinks(projectRoot.resolve(".claude/wiki"))

        return """
            You are maintaining the ClawDEA project wiki for future LLM navigation.
            Return JSON only. Do not include Markdown fences or prose.
            Do not write files or modify the project.
            Prefer cleanup and link normalization over new pages.
            Only propose candidates with concrete evidence and clear future navigation value.

            Output schema:
            {"candidates":[{"kind":"missingConcept|staleConcept|duplicateConcept|indexCleanup|linkNormalization|sourceReferenceFix","title":"Short title","targetFiles":[".claude/wiki/index.md"],"evidence":[{"type":"sourceRef|sessionSignal|wikiProbeMiss|acceptedWikiChange|staleLink|duplicateContent","ref":"path or stable identifier","summary":"Why this evidence matters"}],"usefulness":"How this helps future LLM navigation","contextCost":"shrinks-context|neutral|adds-context","confidence":"high|medium|low","proposedAction":"applyLowRisk|proposeDiff|reportOnly","patchPlan":"Concise edit description, not executable code"}]}

            .claude/wiki/index.md:
            $index

            Wiki page context:
            $wikiContext

            .claude/REPO_STATE.md:
            $repoState

            .claude/notes/CURRENT.md:
            $notes

            Existing wiki source/reference links:
            $links
        """.trimIndent()
    }

    private fun buildWikiPageContext(wikiDir: Path): String {
        if (!Files.isDirectory(wikiDir)) return ""
        val stream = Files.walk(wikiDir)
        val pages = try {
            stream
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".md") }
                .filter { path -> isConceptOrSourcePage(wikiDir.relativize(path).toString()) }
                .sorted()
                .limit(MAX_WIKI_CONTEXT_FILES.toLong())
                .toList()
        } finally {
            stream.close()
        }
        return pages.joinToString("\n\n") { path ->
            val relative = ".claude/wiki/${wikiDir.relativize(path).toString().replace('\\', '/')}"
            "$relative:\n${summarizeMarkdown(readCappedText(path, PAGE_READ_CHAR_CAP))}"
        }.take(WIKI_CONTEXT_CHAR_CAP)
    }

    private fun isConceptOrSourcePage(relativePath: String): Boolean {
        val normalized = relativePath.replace('\\', '/')
        return normalized.startsWith("concepts/") || normalized.startsWith("source/")
    }

    private fun summarizeMarkdown(text: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val headings = lines.filter { it.startsWith("#") }.take(MAX_HEADINGS_PER_PAGE)
        val excerpts = lines.filterNot { it.startsWith("#") }.take(MAX_EXCERPT_LINES_PER_PAGE)
        return (headings + excerpts).joinToString("\n").take(PAGE_SUMMARY_CHAR_CAP)
    }

    private fun collectWikiLinks(wikiDir: Path): String {
        if (!Files.isDirectory(wikiDir)) return ""
        val links = linkedSetOf<String>()
        val stream = Files.walk(wikiDir)
        try {
            stream
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".md") }
                .sorted()
                .limit(MAX_LINK_SCAN_FILES.toLong())
                .forEach { path ->
                    LINK_RX.findAll(readCappedText(path, PAGE_READ_CHAR_CAP))
                        .map { it.groupValues[1].trim() }
                        .filter { it.isNotBlank() && isUsefulReferenceLink(it) }
                        .take(MAX_LINKS_PER_PAGE)
                        .forEach { links += it }
                }
        } finally {
            stream.close()
        }
        return links.take(MAX_REFERENCE_LINKS).joinToString("\n").take(REFERENCE_LINKS_CHAR_CAP)
    }

    private fun isUsefulReferenceLink(link: String): Boolean =
        link.startsWith(".") ||
            link.startsWith("/") ||
            link.contains("src/") ||
            SOURCE_EXTENSION_RX.containsMatchIn(link)

    private fun readCappedText(path: Path, charCap: Int): String {
        if (!Files.isRegularFile(path)) return ""
        val byteCap = (charCap * 4).coerceAtLeast(charCap)
        return try {
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(byteCap)
                val bytesRead = input.read(buffer)
                if (bytesRead <= 0) "" else String(buffer, 0, bytesRead, StandardCharsets.UTF_8).take(charCap)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun isIndexOverContextCap(indexPath: Path): Boolean {
        if (!Files.isRegularFile(indexPath)) return false
        return try {
            Files.size(indexPath) > INDEX_CONTEXT_BYTE_CAP ||
                readCappedText(indexPath, INDEX_CONTEXT_LINE_SCAN_CHAR_CAP).lineSequence().take(INDEX_CONTEXT_LINE_CAP + 1).count() > INDEX_CONTEXT_LINE_CAP
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val INDEX_CHAR_CAP = 6_000
        const val REPO_STATE_CHAR_CAP = 2_000
        const val NOTES_CHAR_CAP = 2_000
        const val PAGE_READ_CHAR_CAP = 2_000
        const val WIKI_CONTEXT_CHAR_CAP = 3_500
        const val PAGE_SUMMARY_CHAR_CAP = 700
        const val REFERENCE_LINKS_CHAR_CAP = 1_500
        const val MAX_WIKI_CONTEXT_FILES = 12
        const val MAX_HEADINGS_PER_PAGE = 6
        const val MAX_EXCERPT_LINES_PER_PAGE = 3
        const val MAX_LINK_SCAN_FILES = 40
        const val MAX_LINKS_PER_PAGE = 6
        const val MAX_REFERENCE_LINKS = 50
        const val INDEX_CONTEXT_LINE_CAP = 200
        const val INDEX_CONTEXT_BYTE_CAP = 25 * 1024L
        const val INDEX_CONTEXT_LINE_SCAN_CHAR_CAP = 32_000
        val LINK_RX = Regex("""\[[^\]]+]\(([^)#]+)(?:#[^)]*)?\)""")
        val SOURCE_EXTENSION_RX = Regex("""\.(kt|java|js|ts|tsx|jsx|md)$""")
    }
}
