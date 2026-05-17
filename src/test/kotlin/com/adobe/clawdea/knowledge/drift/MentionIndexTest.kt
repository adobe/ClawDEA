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

class MentionIndexTest {

    @Test fun `path under src is a valid mention token`() {
        assertTrue(MentionIndex.isValidToken("src/main/kotlin/com/adobe/Foo.kt"))
        assertTrue(MentionIndex.isValidToken("src/test/kotlin/Bar.kt"))
        assertTrue(MentionIndex.isValidToken("bin/main/com/adobe/Foo.kt"))
        assertTrue(MentionIndex.isValidToken("build.gradle.kts"))
        assertTrue(MentionIndex.isValidToken("settings.gradle.kts"))
        assertTrue(MentionIndex.isValidToken("gradle.properties"))
    }

    @Test fun `short or all-lowercase basenames are rejected`() {
        assertFalse(MentionIndex.isValidToken("id"))         // too short
        assertFalse(MentionIndex.isValidToken("path"))       // all lowercase
        assertFalse(MentionIndex.isValidToken("List"))       // too short
        assertFalse(MentionIndex.isValidToken("string"))     // all lowercase
    }

    @Test fun `pascal-case basename of length 6+ is accepted`() {
        assertTrue(MentionIndex.isValidToken("WikiAgentsArg"))
        assertTrue(MentionIndex.isValidToken("CliProcess"))
        assertTrue(MentionIndex.isValidToken("DriftEvent"))
    }

    @Test fun `arbitrary text outside src is rejected unless it meets the basename rule`() {
        assertFalse(MentionIndex.isValidToken("docs/some/file.md"))
        assertFalse(MentionIndex.isValidToken("README.md"))   // 9 chars, but no internal uppercase
    }

    @Test fun `extractKtClassNames extracts class object interface and enum names`() {
        val source = """
            package com.adobe.clawdea
            class FooBar
            object Singleton
            interface Contract
            enum class Color { RED, GREEN }
            data class DataHolder(val x: Int)
            sealed class Sealed
        """.trimIndent()
        val names = MentionIndex.extractKtClassNames(source)
        assertEquals(setOf("FooBar", "Singleton", "Contract", "Color", "DataHolder", "Sealed"), names)
    }

    @Test fun `buildForPage indexes paths and class names from markdown`() {
        val markdown = """
            # WikiAuthor

            Implemented in `src/main/kotlin/com/adobe/Foo.kt` and
            uses `WikiAgentsArg` for the JSON payload. See [BarClass](path/to/Bar.kt).

            Don't include short words like `id` or generic `list` in mentions.
        """.trimIndent()
        val mentions = MentionIndex.buildForPage(markdown)
        assertTrue("WikiAgentsArg in mentions", "WikiAgentsArg" in mentions)
        assertTrue("BarClass in mentions", "BarClass" in mentions)
        assertTrue("src path in mentions", "src/main/kotlin/com/adobe/Foo.kt" in mentions)
        assertFalse("id rejected", "id" in mentions)
        assertFalse("list rejected", "list" in mentions)
    }
}
