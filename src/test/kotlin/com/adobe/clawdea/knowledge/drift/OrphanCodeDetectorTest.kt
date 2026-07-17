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

class OrphanCodeDetectorTest {

    private fun type(name: String, path: String = "src/main/kotlin/$name.kt") =
        OrphanCodeDetector.SourceType(name, path)

    @Test fun `flags a cluster the wiki never mentions`() {
        val types = listOf(
            type("CodexProcess", "src/main/kotlin/com/adobe/clawdea/cli/CodexProcess.kt"),
            type("CodexAppServerProcess", "src/main/kotlin/com/adobe/clawdea/cli/CodexAppServerProcess.kt"),
            type("CodexModelProbe", "src/main/kotlin/com/adobe/clawdea/gateway/CodexModelProbe.kt"),
        )
        val wiki = "# Wiki\nThis plugin wraps the Claude Code CLI as a subprocess."

        val events = OrphanCodeDetector.computeOrphans(types, wiki)

        assertEquals(1, events.size)
        val e = events.single()
        assertEquals("Codex", e.prefix)
        assertEquals(listOf("CodexAppServerProcess", "CodexModelProbe", "CodexProcess"), e.classNames)
        assertTrue(e.representativePaths.any { it.contains("CodexProcess.kt") })
    }

    @Test fun `does not flag when a class name is mentioned in the wiki`() {
        val types = listOf(type("CodexProcess"), type("CodexAppServerProcess"), type("CodexModelProbe"))
        val wiki = "The CodexProcess spawns the app-server backend."

        assertTrue(OrphanCodeDetector.computeOrphans(types, wiki).isEmpty())
    }

    @Test fun `does not flag when the prefix word appears in the wiki`() {
        // Concept-level coverage: prose mentions "profiling" even though the
        // exact class names never appear.
        val types = listOf(type("ProfilingSession"), type("ProfilingBackend"), type("ProfilingReport"))
        val wiki = "JFR profiling captures CPU and allocation data."

        assertTrue(OrphanCodeDetector.computeOrphans(types, wiki).isEmpty())
    }

    @Test fun `does not flag clusters below the minimum size`() {
        val types = listOf(type("CodexProcess"), type("CodexModelProbe")) // only 2
        val wiki = "# Wiki\nNothing relevant."

        assertTrue(OrphanCodeDetector.computeOrphans(types, wiki).isEmpty())
    }

    @Test fun `dedups repeated class names before counting cluster size`() {
        // Same three names discovered twice (e.g. two source roots) is still one
        // cluster of three, not six.
        val names = listOf("CodexProcess", "CodexModelProbe", "CodexAppServerProcess")
        val types = (names + names).map { type(it) }
        val wiki = "# Wiki\nNothing relevant."

        val events = OrphanCodeDetector.computeOrphans(types, wiki)
        assertEquals(1, events.size)
        assertEquals(3, events.single().classNames.size)
    }

    @Test fun `matches class names case-insensitively as whole words`() {
        // "codexprocess" as a substring of a longer word must NOT count as a mention.
        val types = listOf(type("CodexProcess"), type("CodexModelProbe"), type("CodexAppServerProcess"))
        val wiki = "The precodexprocessing step is unrelated."

        assertEquals(1, OrphanCodeDetector.computeOrphans(types, wiki).size)
    }

    @Test fun `skips generic role-word prefixes on the denylist`() {
        val types = listOf(type("HandlerOne"), type("HandlerTwo"), type("HandlerThree"))
        val wiki = "# Wiki\nNo mention of any handler class."

        assertTrue(OrphanCodeDetector.computeOrphans(types, wiki).isEmpty())
    }

    @Test fun `caps representative paths per event`() {
        val suffixes = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta", "Iota", "Kappa")
        val types = suffixes.map { type("Codex$it", "src/main/kotlin/Codex$it.kt") }
        val wiki = "# Wiki\nNothing relevant."

        val e = OrphanCodeDetector.computeOrphans(types, wiki).single()
        assertEquals(OrphanCodeDetector.MAX_PATHS_PER_EVENT, e.representativePaths.size)
    }

    @Test fun `empty inputs yield no events`() {
        assertTrue(OrphanCodeDetector.computeOrphans(emptyList(), "wiki text").isEmpty())
    }
}
