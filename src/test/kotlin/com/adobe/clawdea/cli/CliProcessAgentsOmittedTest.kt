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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards that the main chat CLI no longer injects the wiki `--agents` JSON. That JSON (~14KB)
 * pushed the Windows cmd.exe command line past its 8191-char cap ("The command line is too long.").
 * The in-chat librarian now routes through the `ask_wiki_librarian` MCP tool (see
 * [com.adobe.clawdea.mcp.McpWikiTools]); wiki-author runs out-of-band via WikiAuthorInvoker.
 */
class CliProcessAgentsOmittedTest {

    @Test fun `CliProcess start does not build an --agents argument`() {
        val src = File("src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt").readText()
        // The only permitted mention of the flag is in comments explaining why it's gone.
        val codeLines = src.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "CliProcess must not add \"--agents\" to the main chat argv",
            codeLines.contains("\"--agents\""),
        )
    }

    @Test fun `tool prompt constant is available and non-blank`() {
        assertTrue(CliProcess.WIKI_LIBRARIAN_TOOL_PROMPT.isNotBlank())
    }
}
