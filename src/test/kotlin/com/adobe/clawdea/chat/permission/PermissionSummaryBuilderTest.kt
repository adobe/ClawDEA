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
package com.adobe.clawdea.chat.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSummaryBuilderTest {

    @Test
    fun `Bash summary shows the command`() {
        val summary = PermissionSummaryBuilder.build("Bash", """{"command":"ls -la /etc","description":"list etc"}""")
        assertEquals("ls -la /etc", summary)
    }

    @Test
    fun `Bash summary truncates very long commands`() {
        val long = "a".repeat(200)
        val summary = PermissionSummaryBuilder.build("Bash", """{"command":"$long"}""")
        assertTrue("expected length <= 123, got ${summary.length}", summary.length <= 123)
        assertTrue(summary.endsWith("…"))
    }

    @Test
    fun `Bash summary collapses newlines to spaces`() {
        val summary = PermissionSummaryBuilder.build("Bash", """{"command":"line1\nline2"}""")
        assertEquals("line1 line2", summary)
    }

    @Test
    fun `WebFetch summary shows the url`() {
        val summary = PermissionSummaryBuilder.build("WebFetch", """{"url":"https://example.com/","prompt":"foo"}""")
        assertEquals("https://example.com/", summary)
    }

    @Test
    fun `Edit summary shows the file path`() {
        val summary = PermissionSummaryBuilder.build("Edit", """{"file_path":"/abs/src/Foo.kt","old_string":"x","new_string":"y"}""")
        assertEquals("/abs/src/Foo.kt", summary)
    }

    @Test
    fun `Write summary shows the file path`() {
        val summary = PermissionSummaryBuilder.build("Write", """{"file_path":"/abs/src/Foo.kt","content":"x"}""")
        assertEquals("/abs/src/Foo.kt", summary)
    }

    @Test
    fun `MultiEdit summary shows the file path`() {
        val summary = PermissionSummaryBuilder.build("MultiEdit", """{"file_path":"/abs/src/Foo.kt","edits":[]}""")
        assertEquals("/abs/src/Foo.kt", summary)
    }

    @Test
    fun `NotebookEdit summary shows the notebook path`() {
        val summary = PermissionSummaryBuilder.build("NotebookEdit", """{"notebook_path":"/abs/N.ipynb","cell_id":"c1","new_source":""}""")
        assertEquals("/abs/N.ipynb", summary)
    }

    @Test
    fun `WebSearch summary shows the query`() {
        val summary = PermissionSummaryBuilder.build("WebSearch", """{"query":"kotlin coroutines"}""")
        assertEquals("kotlin coroutines", summary)
    }

    @Test
    fun `unknown tool falls back to first two fields`() {
        val summary = PermissionSummaryBuilder.build(
            "CustomTool",
            """{"first":"alpha","second":"beta","third":"gamma"}""",
        )
        assertTrue("expected alpha and beta in $summary", summary.contains("first=alpha"))
        assertTrue("expected second=beta in $summary", summary.contains("second=beta"))
    }

    @Test
    fun `missing required field falls back to tool name`() {
        val summary = PermissionSummaryBuilder.build("Bash", """{"nope":"x"}""")
        assertEquals("Bash", summary)
    }

    @Test
    fun `empty input falls back to tool name`() {
        val summary = PermissionSummaryBuilder.build("Bash", "")
        assertEquals("Bash", summary)
    }
}
