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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ManifestStaleDetectorTest {

    private fun mkManifest(workspace: Path, content: String): Path {
        val file = workspace.resolve(".clawdea-workspace.md")
        Files.writeString(file, content)
        return file
    }

    @Test fun `missing repo path emits event with line hint`() {
        val ws = Files.createTempDirectory("ws")
        Files.createDirectories(ws.resolve("alive-repo"))
        val manifestText = """
            # Workspace: ws

            ## Repos: g

            - **alive** `alive-repo` — alive
            - **dead** `missing-repo` — was here once
        """.trimIndent()
        val manifest = mkManifest(ws, manifestText)
        val events = ManifestStaleDetector.detect(manifest)
        assertEquals(1, events.size)
        val ev = events.single() as DriftEvent.ManifestStale
        assertEquals("dead", ev.repoKey)
        assertEquals("g", ev.groupName)
        assertEquals(manifest, ev.manifestPath)
        // The dead bullet is on a known line of the manifest (1-based).
        val deadLineNumber = manifestText.lines().indexOfFirst { it.contains("**dead**") } + 1
        assertEquals(deadLineNumber, ev.lineHint)
    }

    @Test fun `all repos present produces no events`() {
        val ws = Files.createTempDirectory("ws")
        Files.createDirectories(ws.resolve("a"))
        Files.createDirectories(ws.resolve("b"))
        val manifest = mkManifest(ws, """
            # Workspace: ws

            ## Repos: g

            - **a** `a` — a
            - **b** `b` — b
        """.trimIndent())
        assertEquals(emptyList<DriftEvent>(), ManifestStaleDetector.detect(manifest))
    }

    @Test fun `multi-group manifest with one stale per group`() {
        val ws = Files.createTempDirectory("ws")
        Files.createDirectories(ws.resolve("alive1"))
        Files.createDirectories(ws.resolve("alive2"))
        val manifest = mkManifest(ws, """
            # Workspace: ws

            ## Repos: g1

            - **alive1** `alive1` — a
            - **dead1** `missing1` — d

            ## Repos: g2

            - **alive2** `alive2` — a
            - **dead2** `missing2` — d
        """.trimIndent())
        val events = ManifestStaleDetector.detect(manifest)
        assertEquals(2, events.size)
        val keys = events.map { (it as DriftEvent.ManifestStale).repoKey }.toSet()
        assertTrue("dead1" in keys && "dead2" in keys)
        val groups = events.map { (it as DriftEvent.ManifestStale).groupName }.toSet()
        assertTrue("g1" in groups && "g2" in groups)
    }

    @Test fun `nonexistent manifest returns empty list`() {
        val ws = Files.createTempDirectory("ws")
        val events = ManifestStaleDetector.detect(ws.resolve(".clawdea-workspace.md"))
        assertEquals(emptyList<DriftEvent>(), events)
    }
}
