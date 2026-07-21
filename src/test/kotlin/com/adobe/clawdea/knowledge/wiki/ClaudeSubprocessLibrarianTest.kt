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
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.provider.AgentSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class ClaudeSubprocessLibrarianTest {
    private var capturedCommand: List<String>? = null

    private fun libWith(result: ClaudeSubprocessLibrarian.RunResult) = ClaudeSubprocessLibrarian(
        claudeCliPath = "claude",
        projectRoot = Path.of("."),
        mcpPort = 0,
        selection = AgentSelection("bedrock", null, "claude-haiku"),
        timeoutSeconds = 30,
        runner = object : ClaudeSubprocessLibrarian.Runner {
            override fun run(command: List<String>, projectRoot: Path, selection: AgentSelection, timeoutSeconds: Long): ClaudeSubprocessLibrarian.RunResult {
                capturedCommand = command
                return result
            }
        },
    )

    @Test fun returns_assistant_text_from_stream_json() {
        val stdout = listOf(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"The bridge owns the process."}]}}""",
            """{"type":"result","subtype":"success","is_error":false,"result":"The bridge owns the process."}""",
        ).joinToString("\n")
        val ans = libWith(ClaudeSubprocessLibrarian.RunResult(0, stdout, "", timedOut = false)).ask("How does the bridge work?")
        assertFalse(ans.isError)
        assertTrue(ans.text.contains("bridge owns the process"))
    }

    @Test fun timeout_is_error() {
        val ans = libWith(ClaudeSubprocessLibrarian.RunResult(-1, "", "", timedOut = true)).ask("q")
        assertTrue(ans.isError)
        assertTrue(ans.text.contains("timed out"))
    }

    @Test fun nonzero_exit_with_no_text_is_error() {
        val ans = libWith(ClaudeSubprocessLibrarian.RunResult(1, "", "boom", timedOut = false)).ask("q")
        assertTrue(ans.isError)
    }

    @Test fun errored_turn_with_partial_text_reports_error() {
        val stdout = listOf(
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"I found "}}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"partial info"}}}""",
            """{"type":"result","subtype":"error","is_error":true,"result":""}""",
        ).joinToString("\n")
        val ans = libWith(ClaudeSubprocessLibrarian.RunResult(0, stdout, "", timedOut = false)).ask("test")
        assertTrue("Should report error when result.is_error=true", ans.isError)
        assertTrue("Should still return partial text", ans.text.contains("I found partial info"))
    }

    @Test fun allowlist_contains_only_read_tools() {
        capturedCommand = null
        libWith(ClaudeSubprocessLibrarian.RunResult(0, "", "", timedOut = false)).ask("test")
        val cmd = capturedCommand!!

        // Must contain --allowedTools flag
        assertTrue("Must use --allowedTools", cmd.contains("--allowedTools"))
        val allowedIdx = cmd.indexOf("--allowedTools")
        val allowedTools = cmd[allowedIdx + 1]

        // Must contain the read-only tools
        assertTrue("Must allow Read", allowedTools.contains("Read"))
        assertTrue("Must allow read_wiki_page", allowedTools.contains("mcp__clawdea-intellij__read_wiki_page"))
        assertTrue("Must allow search_text", allowedTools.contains("mcp__clawdea-intellij__search_text"))
        assertTrue("Must allow find_files", allowedTools.contains("mcp__clawdea-intellij__find_files"))

        // Must NOT contain write/mutating tools
        assertFalse("Must not allow Bash", allowedTools.contains("Bash"))
        assertFalse("Must not allow Write", allowedTools.contains("Write"))
        assertFalse("Must not allow Edit", allowedTools.contains("Edit"))
        assertFalse("Must not allow NotebookEdit", allowedTools.contains("NotebookEdit"))
        assertFalse("Must not allow debug_evaluate", allowedTools.contains("debug_evaluate"))
        assertFalse("Must not allow debug_launch_adhoc", allowedTools.contains("debug_launch_adhoc"))

        // Must still use bypassPermissions (no UI in subprocess)
        assertTrue("Must use bypassPermissions", cmd.contains("bypassPermissions"))

        // Must not have old --disallowedTools
        assertFalse("Must not use --disallowedTools", cmd.contains("--disallowedTools"))
    }
}
