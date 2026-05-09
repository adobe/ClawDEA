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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ClaudePermissionSettingsTest {

    @Test
    fun `reader extracts allow and deny rules from supported settings files`() {
        val root = Files.createTempDirectory("clawdea-permissions")
        val home = root.resolve("home")
        val project = root.resolve("project")
        Files.createDirectories(home.resolve(".claude"))
        Files.createDirectories(project.resolve(".claude"))
        home.resolve(".claude/settings.json").writeText(
            """{"permissions":{"allow":["Bash(./gradlew build)"],"deny":["Read(./.env)"]}}""",
        )
        project.resolve(".claude/settings.local.json").writeText(
            """{"permissions":{"allow":["Bash(./gradlew *)"]}}""",
        )

        val settings = ClaudePermissionSettingsReader(home, project).read()

        assertEquals(
            listOf("Bash(./gradlew build)", "Bash(./gradlew *)"),
            settings.allow.map { it.raw },
        )
        assertEquals(listOf("Read(./.env)"), settings.deny.map { it.raw })
        assertTrue(settings.warnings.isEmpty())
    }

    @Test
    fun `reader skips malformed and future-shaped settings without failing closed`() {
        val root = Files.createTempDirectory("clawdea-permissions")
        val home = root.resolve("home")
        val project = root.resolve("project")
        Files.createDirectories(home.resolve(".claude"))
        Files.createDirectories(project.resolve(".claude"))
        home.resolve(".claude/settings.json").writeText("""{"permissions": "future-shape"}""")
        project.resolve(".claude/settings.json").writeText("""{not-json""")
        project.resolve(".claude/settings.local.json").writeText(
            """{"permissions":{"allow":["Bash(ls)",42,{"tool":"Read"}],"deny":"nope"}}""",
        )

        val settings = ClaudePermissionSettingsReader(home, project).read()

        assertEquals(listOf("Bash(ls)"), settings.allow.map { it.raw })
        assertTrue(settings.deny.isEmpty())
        assertTrue(settings.warnings.size >= 3)
    }

    @Test
    fun `policy denies before allowing and matches Bash wildcards`() {
        val policy = PermissionPolicy(
            settingsProvider = {
                ClaudePermissionSettings(
                    allow = listOf(ClaudePermissionRule.parse("Bash(./gradlew *)")!!),
                    deny = listOf(ClaudePermissionRule.parse("Bash(./gradlew publish *)")!!),
                )
            },
        )

        assertEquals(
            PermissionPolicy.Decision.ALLOW,
            policy.evaluate("Bash", """{"command":"./gradlew build"}""").decision,
        )
        assertEquals(
            PermissionPolicy.Decision.DENY,
            policy.evaluate("Bash", """{"command":"./gradlew publish release"}""").decision,
        )
        assertEquals(
            PermissionPolicy.Decision.ASK,
            policy.evaluate("Bash", """{"command":"npm test"}""").decision,
        )
    }

    @Test
    fun `writer appends allow rule to local settings and preserves unrelated fields`() {
        val project = Files.createTempDirectory("clawdea-permissions-project")
        val settingsDir = project.resolve(".claude")
        Files.createDirectories(settingsDir)
        val localSettings = settingsDir.resolve("settings.local.json")
        localSettings.writeText(
            """
            {
              "${'$'}schema": "https://json.schemastore.org/claude-code-settings.json",
              "env": {"FOO": "bar"},
              "permissions": {
                "deny": ["Read(./.env)"],
                "allow": ["Bash(npm test)"],
                "ask": ["WebFetch"]
              }
            }
            """.trimIndent(),
        )

        val result = ClaudePermissionSettingsWriter(project).appendAllowRule("Bash(./gradlew build)")

        assertTrue(result.success)
        val updated = localSettings.readText()
        assertTrue(updated.contains(""""env""""))
        assertTrue(updated.contains(""""ask""""))
        assertTrue(updated.contains("Bash(npm test)"))
        assertTrue(updated.contains("Bash(./gradlew build)"))
        assertTrue(updated.contains("Read(./.env)"))
    }

    @Test
    fun `writer reports failure instead of overwriting malformed settings`() {
        val project = Files.createTempDirectory("clawdea-permissions-project")
        val settingsDir = project.resolve(".claude")
        Files.createDirectories(settingsDir)
        settingsDir.resolve("settings.local.json").writeText("""{not-json""")

        val result = ClaudePermissionSettingsWriter(project).appendAllowRule("Bash(./gradlew build)")

        assertFalse(result.success)
        assertEquals("""{not-json""", settingsDir.resolve("settings.local.json").readText())
    }
}
