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

import com.adobe.clawdea.approval.PermissionRequest
import com.adobe.clawdea.chat.MessageRenderer
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRequestRendererTest {

    // The auto-allowed standalone notice was removed: a single project-level
    // PermissionDispatcher rendered it via appendHtml, which lands in whichever
    // ChatPanel is focused, not the one that owns the originating tool call.
    // The marker is now inlined on the tool's Output row by MessageRenderer.
    // See MessageRendererTest.

    @Test
    fun `permission card includes always allow scope choices`() {
        val renderer = PermissionRequestRenderer(MessageRenderer())
        val request = PermissionRequest(
            requestId = "perm-1",
            toolName = "Bash",
            inputJson = """{"command":"./gradlew build"}""",
            summary = "./gradlew build",
        )

        val html = renderer.renderCard(request)

        assertTrue(html.contains("Always allow..."))
        assertTrue(html.contains("""data-action="permission-always""""))
        assertTrue(html.contains("This exact command/input"))
        assertTrue(html.contains("Similar commands"))
        assertTrue(html.contains("All calls to this tool"))
    }

    @Test
    fun `permission card contains only action buttons without redundant tool info`() {
        val renderer = PermissionRequestRenderer(MessageRenderer())
        val request = PermissionRequest(
            requestId = "perm-1",
            toolName = "Bash",
            inputJson = """{"command":"./gradlew build"}""",
            summary = "./gradlew build",
        )

        val html = renderer.renderCard(request)

        assertTrue(html.contains("permission-allow-btn"))
        assertTrue(html.contains("permission-deny-btn"))
        assertTrue(!html.contains("Approve tool:"))
        assertTrue(!html.contains("Exact command/input"))
    }
}
