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
package com.adobe.clawdea.knowledge.repostate.sections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitSectionGeneratorTest {

    @Test
    fun `parseLog extracts commits and ranks hot files`() {
        val log = """
            COMMIT|abc1234|Refactor RolloutManagerImpl
            bundles/acme-modules-core/src/main/java/RolloutManagerImpl.java
            bundles/acme-modules-core/src/main/java/CompositeTreeWalker.java

            COMMIT|def5678|Fix CompositeTreeWalker traversal
            bundles/acme-modules-core/src/main/java/CompositeTreeWalker.java

            COMMIT|ghi9012|Tweak CompositeTreeWalker depth
            bundles/acme-modules-core/src/main/java/CompositeTreeWalker.java
        """.trimIndent()

        val parsed = GitSectionGenerator.parseLog(log)

        assertEquals(3, parsed.commits.size)
        assertEquals("abc1234", parsed.commits[0].sha)
        assertEquals("Refactor RolloutManagerImpl", parsed.commits[0].subject)
        assertEquals("bundles/acme-modules-core/src/main/java/CompositeTreeWalker.java", parsed.hotFiles[0].path)
        assertEquals(3, parsed.hotFiles[0].editCount)
    }

    @Test
    fun `parseLog returns empty for blank input`() {
        val parsed = GitSectionGenerator.parseLog("")
        assertTrue(parsed.commits.isEmpty())
        assertTrue(parsed.hotFiles.isEmpty())
    }

    @Test
    fun `formatBody includes branch and caps commits at 5 hot files at 10`() {
        val parsed = GitSectionGenerator.ParsedLog(
            commits = (1..10).map { GitSectionGenerator.Commit("sha$it", "Subject $it") },
            hotFiles = (1..15).map { GitSectionGenerator.HotFile("file$it.java", it) },
        )
        val body = GitSectionGenerator.formatBody(branch = "composite-cf", parsed = parsed)

        assertTrue(body.contains("Branch: composite-cf"))
        assertTrue(body.contains("sha5 Subject 5"))
        assertTrue(!body.contains("sha6 Subject 6"))
        assertTrue(body.contains("file10.java (10 edits)"))
        assertTrue(!body.contains("file11.java"))
    }

    @Test
    fun `formatBody omits hot files line when none`() {
        val parsed = GitSectionGenerator.ParsedLog(commits = emptyList(), hotFiles = emptyList())
        val body = GitSectionGenerator.formatBody(branch = "main", parsed = parsed)
        assertTrue(body.contains("Branch: main"))
        assertTrue(!body.contains("Hot files"))
    }

    @Test
    fun `parseLog ignores malformed lines`() {
        val log = """
            COMMIT|abc1234|First commit
            file-a.java
            garbage line with no leading marker

            COMMIT|def5678|Second commit
            file-b.java
        """.trimIndent()

        val parsed = GitSectionGenerator.parseLog(log)
        assertEquals(2, parsed.commits.size)
        assertEquals(2, parsed.hotFiles.size)
    }
}
