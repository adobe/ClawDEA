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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class DreamWikiDetectorTest {

    private val now: Instant = Instant.parse("2026-05-09T12:00:00Z")

    @Test fun `does not invoke dreams when due gate fails and force is false`() {
        val invocation = RecordingDreamInvocation(validOutput())
        val result = DreamWikiDetector(invocation).detect(
            projectRoot = projectRootWithIndex("index content"),
            state = readyState().copy(dreamLastSuccessfulScanAt = "2026-05-09T11:00:00Z"),
            settings = DreamWikiSettings(enabled = true),
            now = now,
            force = false,
            activeTurn = false,
        )

        assertEquals(emptyList<DriftEvent>(), result.events)
        assertEquals(0, result.filteredCandidateCount)
        assertTrue(result.status.startsWith("not-due:"))
        assertTrue(result.status.contains("elapsed-time"))
        assertFalse(result.attempted)
        assertFalse(result.successful)
        assertFalse(invocation.called)
    }

    @Test fun `forced scan maps valid output to drift event`() {
        val invocation = RecordingDreamInvocation(validOutput())
        val projectRoot = projectRootWithIndex("index content")

        val result = DreamWikiDetector(invocation).detect(
            projectRoot = projectRoot,
            state = DriftState(),
            settings = DreamWikiSettings(enabled = false),
            now = now,
            force = true,
            activeTurn = true,
        )

        assertEquals("ok", result.status)
        assertEquals(0, result.filteredCandidateCount)
        assertEquals(1, result.events.size)
        val event = result.events.single()
        assertTrue(event is DriftEvent.DreamLinkNormalization)
        event as DriftEvent.DreamLinkNormalization
        assertEquals(projectRoot.resolve(".claude/wiki/index.md").normalize(), event.targetFile)
        assertEquals("Normalize concept links", event.title)
        assertTrue(event.autoApplicable)
        assertTrue(result.attempted)
        assertTrue(result.successful)
        assertTrue(invocation.called)
    }

    @Test fun `unavailable invocation returns status and no events`() {
        val invocation = RecordingDreamInvocation(DreamInvocationResult.Unavailable("Dreams timed out"))

        val result = DreamWikiDetector(invocation).detect(
            projectRoot = projectRootWithIndex("index content"),
            state = DriftState(),
            settings = DreamWikiSettings(enabled = true),
            now = now,
            force = true,
            activeTurn = false,
        )

        assertEquals(emptyList<DriftEvent>(), result.events)
        assertEquals("Dreams timed out", result.status)
        assertEquals(0, result.filteredCandidateCount)
        assertTrue(result.attempted)
        assertFalse(result.successful)
        assertTrue(invocation.called)
    }

    @Test fun `invalid JSON returns validation status and no events`() {
        val invocation = RecordingDreamInvocation(DreamInvocationResult.Available("""{"candidates": ["""))

        val result = DreamWikiDetector(invocation).detect(
            projectRoot = projectRootWithIndex("index content"),
            state = DriftState(),
            settings = DreamWikiSettings(enabled = true),
            now = now,
            force = true,
            activeTurn = false,
        )

        assertEquals(emptyList<DriftEvent>(), result.events)
        assertEquals(0, result.filteredCandidateCount)
        assertTrue(result.status.startsWith("invalid:"))
        assertTrue(result.status.contains("Malformed JSON"))
        assertTrue(result.attempted)
        assertFalse(result.successful)
    }

    @Test fun `prompt requires json only no writes cleanup preference and caps index content`() {
        val longIndex = "a".repeat(13_000) + "SHOULD_NOT_APPEAR"
        val invocation = RecordingDreamInvocation(DreamInvocationResult.Available("""{"candidates": []}"""))

        DreamWikiDetector(invocation).detect(
            projectRoot = projectRootWithIndex(longIndex),
            state = DriftState(),
            settings = DreamWikiSettings(enabled = true),
            now = now,
            force = true,
            activeTurn = false,
        )

        val prompt = invocation.prompt
        assertTrue(prompt.contains("Return JSON only"))
        assertTrue(prompt.contains("Do not write files"))
        assertTrue(prompt.contains("Prefer cleanup and link normalization over new pages"))
        assertTrue(prompt.contains(".claude/wiki/index.md"))
        assertFalse(prompt.contains("SHOULD_NOT_APPEAR"))
        assertTrue(prompt.length < 13_000)
    }

    @Test fun `filtered candidate count reflects candidates removed by scorer`() {
        val invocation = RecordingDreamInvocation(validOutput(candidateJson = lowSignalAddsContextCandidateJson()))

        val result = DreamWikiDetector(invocation).detect(
            projectRoot = projectRootWithIndex("index content"),
            state = DriftState(),
            settings = DreamWikiSettings(enabled = true),
            now = now,
            force = true,
            activeTurn = false,
        )

        assertEquals(emptyList<DriftEvent>(), result.events)
        assertEquals("ok", result.status)
        assertEquals(1, result.filteredCandidateCount)
        assertTrue(result.attempted)
        assertTrue(result.successful)
    }

    @Test fun `low confidence candidates do not map to events`() {
        val invocation = RecordingDreamInvocation(validOutput(candidateJson = candidateJson(confidence = "low", proposedAction = "reportOnly")))

        val result = DreamWikiDetector(invocation).detect(
            projectRoot = projectRootWithIndex("index content"),
            state = DriftState(),
            settings = DreamWikiSettings(enabled = true),
            now = now,
            force = true,
            activeTurn = false,
        )

        assertEquals(emptyList<DriftEvent>(), result.events)
        assertEquals("ok", result.status)
        assertEquals(1, result.filteredCandidateCount)
    }

    @Test fun `oversized patch plans do not map to events`() {
        val invocation = RecordingDreamInvocation(validOutput(candidateJson = candidateJson(patchPlan = "x".repeat(4_001))))

        val result = DreamWikiDetector(invocation).detect(
            projectRoot = projectRootWithIndex("index content"),
            state = DriftState(),
            settings = DreamWikiSettings(enabled = true),
            now = now,
            force = true,
            activeTurn = false,
        )

        assertEquals(emptyList<DriftEvent>(), result.events)
        assertEquals("ok", result.status)
        assertEquals(1, result.filteredCandidateCount)
    }

    @Test fun `add-context index candidates do not map to events when index is over cap`() {
        val longIndex = (1..201).joinToString("\n") { "- [Entry $it](concepts/$it.md)" }
        val invocation = RecordingDreamInvocation(
            validOutput(
                candidateJson = candidateJson(
                    kind = "missingConcept",
                    targetFile = ".claude/wiki/index.md",
                    contextCost = "adds-context",
                    proposedAction = "proposeDiff",
                    evidenceCount = 2,
                    patchPlan = "Add another index entry.",
                ),
            ),
        )

        val result = DreamWikiDetector(invocation).detect(
            projectRoot = projectRootWithIndex(longIndex),
            state = DriftState(),
            settings = DreamWikiSettings(enabled = true),
            now = now,
            force = true,
            activeTurn = false,
        )

        assertEquals(emptyList<DriftEvent>(), result.events)
        assertEquals("ok", result.status)
        assertEquals(1, result.filteredCandidateCount)
    }

    @Test fun `prompt includes bounded local wiki repo state notes and references`() {
        val root = projectRootWithIndex("# Wiki\n\n- [Drift](concepts/drift.md)")
        val concept = root.resolve(".claude/wiki/concepts/drift.md")
        Files.createDirectories(concept.parent)
        Files.writeString(
            concept,
            """
            # Drift Detector

            Short useful excerpt about drift maintenance.

            ## Signals

            See [DreamWikiDetector](../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DreamWikiDetector.kt)
            ${"z".repeat(2_000)}
            SHOULD_NOT_APPEAR
            """.trimIndent(),
        )
        Files.writeString(root.resolve(".claude/REPO_STATE.md"), "# Repo State\n\nDream work is active.")
        Files.createDirectories(root.resolve(".claude/notes"))
        Files.writeString(root.resolve(".claude/notes/CURRENT.md"), "# Notes\n\nRemember dream guardrails.")
        val invocation = RecordingDreamInvocation(DreamInvocationResult.Available("""{"candidates": []}"""))

        DreamWikiDetector(invocation).detect(
            projectRoot = root,
            state = DriftState(),
            settings = DreamWikiSettings(enabled = true),
            now = now,
            force = true,
            activeTurn = false,
        )

        val prompt = invocation.prompt
        assertTrue(prompt.contains("Wiki page context:"))
        assertTrue(prompt.contains(".claude/wiki/concepts/drift.md"))
        assertTrue(prompt.contains("# Drift Detector"))
        assertTrue(prompt.contains("Short useful excerpt"))
        assertTrue(prompt.contains("## Signals"))
        assertTrue(prompt.contains(".claude/REPO_STATE.md:"))
        assertTrue(prompt.contains("Dream work is active."))
        assertTrue(prompt.contains(".claude/notes/CURRENT.md:"))
        assertTrue(prompt.contains("Remember dream guardrails."))
        assertTrue(prompt.contains("Existing wiki source/reference links:"))
        assertTrue(prompt.contains("../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DreamWikiDetector.kt"))
        assertFalse(prompt.contains("SHOULD_NOT_APPEAR"))
        assertTrue(prompt.length < 18_000)
    }

    private fun projectRootWithIndex(indexContent: String): Path {
        val root = Files.createTempDirectory("dream-detector")
        val wikiDir = root.resolve(".claude/wiki")
        Files.createDirectories(wikiDir)
        Files.writeString(wikiDir.resolve("index.md"), indexContent)
        return root
    }

    private fun readyState(): DriftState = DriftState(
        dreamLastSuccessfulScanAt = "2026-05-08T11:00:00Z",
        dreamLastDueCheckAt = "2026-05-09T11:00:00Z",
        dreamProcessedSignalUnits = 3,
        dreamObservedSignalUnits = 9,
    )

    private fun validOutput(candidateJson: String = validCandidateJson()): DreamInvocationResult.Available =
        DreamInvocationResult.Available("""{"candidates": [$candidateJson]}""")

    private fun validCandidateJson(): String = """
        {
          "kind": "linkNormalization",
          "title": "Normalize concept links",
          "targetFiles": [".claude/wiki/index.md"],
          "evidence": [
            {
              "type": "staleLink",
              "ref": ".claude/wiki/index.md",
              "summary": "Index still uses legacy wikilinks."
            }
          ],
          "usefulness": "Keeps wiki references parseable for future navigation.",
          "contextCost": "neutral",
          "confidence": "high",
          "proposedAction": "applyLowRisk",
          "patchPlan": "Replace legacy wikilinks with Markdown links."
        }
    """.trimIndent()

    private fun lowSignalAddsContextCandidateJson(): String = """
        {
          "kind": "missingConcept",
          "title": "Add vague concept",
          "targetFiles": [".claude/wiki/concepts/vague.md"],
          "evidence": [
            {
              "type": "sessionSignal",
              "ref": "session-1",
              "summary": "A single mention appeared."
            }
          ],
          "usefulness": "May help later.",
          "contextCost": "adds-context",
          "confidence": "low",
          "proposedAction": "proposeDiff",
          "patchPlan": "Add a new page."
        }
    """.trimIndent()

    private fun candidateJson(
        kind: String = "linkNormalization",
        targetFile: String = ".claude/wiki/index.md",
        contextCost: String = "neutral",
        confidence: String = "high",
        proposedAction: String = "applyLowRisk",
        evidenceCount: Int = 1,
        patchPlan: String = "Replace legacy wikilinks with Markdown links.",
    ): String {
        val evidence = (1..evidenceCount).joinToString(",") { index ->
            """
            {
              "type": "staleLink",
              "ref": ".claude/wiki/index.md#$index",
              "summary": "Index still uses legacy wikilinks."
            }
            """.trimIndent()
        }
        return """
            {
              "kind": "$kind",
              "title": "Normalize concept links",
              "targetFiles": ["$targetFile"],
              "evidence": [$evidence],
              "usefulness": "Keeps wiki references parseable for future navigation.",
              "contextCost": "$contextCost",
              "confidence": "$confidence",
              "proposedAction": "$proposedAction",
              "patchPlan": "$patchPlan"
            }
        """.trimIndent()
    }

    private class RecordingDreamInvocation(private val result: DreamInvocationResult) : DreamInvocation {
        var called: Boolean = false
        var projectRoot: Path? = null
        var prompt: String = ""

        override fun run(projectRoot: Path, prompt: String): DreamInvocationResult {
            called = true
            this.projectRoot = projectRoot
            this.prompt = prompt
            return result
        }
    }
}
