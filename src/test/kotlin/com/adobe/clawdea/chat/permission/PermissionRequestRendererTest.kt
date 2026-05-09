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

import com.adobe.clawdea.chat.MessageRenderer
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRequestRendererTest {

    @Test
    fun `auto-allowed notice explicitly flags allow-all execution`() {
        val renderer = PermissionRequestRenderer(MessageRenderer())
        val request = PermissionRequest(
            requestId = "perm-1",
            toolName = "Bash",
            inputJson = """{"command":"mvn test"}""",
            summary = "mvn test",
        ).apply {
            resolve(PermissionRequest.Decision.ALLOW)
        }

        val html = renderer.renderAutoAllowedNotice(request)

        assertTrue(html.contains("Auto-allowed by Tool approval = Allow all"))
        assertTrue(html.contains("Auto-allowed: <code>Bash</code>"))
        assertTrue(html.contains("mvn test"))
    }

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
}
