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
package com.adobe.clawdea.cli

import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression guard for issue #94.
 *
 * ClawDEA deliberately does not pass `--include-hook-events` to the Claude Code
 * CLI. The flag would emit hook lifecycle events (`PreToolUse`, `PostToolUse`,
 * `Stop`, `SessionStart`, `SessionEnd`, `UserPromptSubmit`, `PreCompact`,
 * `Notification`) into stream-json output, but ClawDEA already provides
 * first-class UX for those moments (edit review via propose_edit, permission
 * approval via the MCP request_permission tool, session start/end via the chat
 * panel). User-configured hooks would duplicate or conflict with that UX, and
 * CliEventParser does not model the hook event shapes.
 *
 * If upstream changes hook semantics (or ClawDEA decides to model them), revisit
 * the policy in CliProcess.kt and remove or update this test.
 *
 * Source-level check: the simplest assertion is that the flag string never
 * appears in the two CLI-launching files. A future PR that adds it will trip
 * this test before the change ships.
 */
class CliProcessHookEventsOmissionTest {

    @Test
    fun `CliProcess does not pass --include-hook-events`() {
        val source = readSource("src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt")
        assertFalse(
            "CliProcess.kt must not pass --include-hook-events. See issue #94 and the comment in CliProcess.start.",
            sourceMentionsFlag(source),
        )
    }

    @Test
    fun `ClaudeGateway does not pass --include-hook-events`() {
        val source = readSource("src/main/kotlin/com/adobe/clawdea/gateway/ClaudeGateway.kt")
        assertFalse(
            "ClaudeGateway.kt must not pass --include-hook-events. The gateway uses claude -p with stream-json; hook events are not consumed by any gateway handler.",
            sourceMentionsFlag(source),
        )
    }

    /**
     * The flag string can appear inside Kdoc / comments (e.g. cross-references
     * or rationale notes) without actually being passed to the CLI. Strip
     * Kotlin // line comments and /* */ block comments before checking.
     */
    private fun sourceMentionsFlag(source: String): Boolean {
        val withoutBlockComments = Regex("/\\*[\\s\\S]*?\\*/").replace(source, "")
        val withoutLineComments = withoutBlockComments
            .lineSequence()
            .joinToString("\n") { line ->
                val idx = line.indexOf("//")
                if (idx == -1) line else line.substring(0, idx)
            }
        return withoutLineComments.contains("--include-hook-events")
    }

    private fun readSource(relativePath: String): String {
        val path = Path.of(System.getProperty("user.dir")).resolve(relativePath)
        return Files.readString(path)
    }
}
