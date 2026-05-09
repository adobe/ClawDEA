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
// src/test/kotlin/com/adobe/clawdea/context/GitCollectorTest.kt
package com.adobe.clawdea.context

import org.junit.Assert.*
import org.junit.Test

class GitCollectorTest {

    @Test
    fun `parseDiffOutput parses unified diff correctly`() {
        val diff = """
            diff --git a/src/Main.java b/src/Main.java
            index 1234567..abcdefg 100644
            --- a/src/Main.java
            +++ b/src/Main.java
            @@ -10,6 +10,8 @@ public class Main {
                 public void run() {
            +        System.out.println("hello");
            +        System.out.println("world");
                 }
             }
        """.trimIndent()

        val items = GitCollector.parseDiffOutput(diff)

        assertEquals(1, items.size)
        assertEquals("Uncommitted changes", items[0].label)
        assertTrue(items[0].content.contains("System.out.println"))
        assertEquals("git", items[0].source)
    }

    @Test
    fun `parseDiffOutput returns empty for no changes`() {
        val items = GitCollector.parseDiffOutput("")
        assertTrue(items.isEmpty())
    }

    @Test
    fun `parseLogOutput parses git log correctly`() {
        val log = """
            abc1234 Fix login bug
            def5678 Add user validation
            ghi9012 Refactor auth module
        """.trimIndent()

        val items = GitCollector.parseLogOutput(log)

        assertEquals(1, items.size)
        assertEquals("Recent commits", items[0].label)
        assertTrue(items[0].content.contains("Fix login bug"))
        assertTrue(items[0].content.contains("Refactor auth module"))
    }
}
