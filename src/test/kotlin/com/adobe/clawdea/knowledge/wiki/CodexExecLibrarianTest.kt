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

/**
 * Pure-unit test for [CodexExecLibrarian] via an injected [CodexExecLibrarian.Runner] (no real codex
 * process). Asserts the contract: the final `agent_message` text is returned on success; timeout /
 * `turn.failed` / empty output map to an error answer; and the command is built read-only (no
 * `danger-full-access`, no MCP wiring).
 */
class CodexExecLibrarianTest {
    private var captured: List<String> = emptyList()

    private fun libWith(result: CodexExecLibrarian.RunResult, modelId: String = "gpt-5-codex") =
        CodexExecLibrarian(
            codexCliPath = "codex",
            projectRoot = Path.of("."),
            selection = AgentSelection("openai", null, modelId),
            timeoutSeconds = 30,
            runner = object : CodexExecLibrarian.Runner {
                override fun run(command: List<String>, projectRoot: Path, selection: AgentSelection, timeoutSeconds: Long): CodexExecLibrarian.RunResult {
                    captured = command
                    return result
                }
            },
        )

    @Test fun returns_last_agent_message_from_exec_stream() {
        val stdout = listOf(
            """{"type":"thread.started","thread_id":"t1"}""",
            """{"type":"turn.started"}""",
            """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"Let me check the wiki."}}""",
            """{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"The bridge owns the process lifecycle."}}""",
            """{"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":5}}""",
        ).joinToString("\n")
        val ans = libWith(CodexExecLibrarian.RunResult(0, stdout, "", timedOut = false)).ask("How does the bridge work?")
        assertFalse(ans.isError)
        // The LAST non-blank agent_message wins (preamble item is superseded by the final answer).
        assertTrue(ans.text.contains("bridge owns the process"))
        assertFalse(ans.text.contains("Let me check the wiki"))
    }

    @Test fun timeout_is_error() {
        val ans = libWith(CodexExecLibrarian.RunResult(-1, "", "", timedOut = true)).ask("q")
        assertTrue(ans.isError)
        assertTrue(ans.text.contains("timed out"))
    }

    @Test fun turn_failed_is_error_with_message() {
        val stdout = listOf(
            """{"type":"thread.started","thread_id":"t1"}""",
            """{"type":"turn.failed","error":{"message":"401 Unauthorized"}}""",
        ).joinToString("\n")
        val ans = libWith(CodexExecLibrarian.RunResult(1, stdout, "", timedOut = false)).ask("q")
        assertTrue(ans.isError)
        assertTrue(ans.text.contains("401") || ans.text.contains("Unauthorized"))
    }

    @Test fun empty_output_is_error() {
        val ans = libWith(CodexExecLibrarian.RunResult(1, "", "boom", timedOut = false)).ask("q")
        assertTrue(ans.isError)
    }

    @Test fun command_is_read_only_with_no_danger_access_or_mcp() {
        libWith(CodexExecLibrarian.RunResult(0, """{"type":"item.completed","item":{"type":"agent_message","text":"ok"}}""", "", false)).ask("q")
        val joined = captured.joinToString(" ")
        assertTrue("must run exec --json", captured.contains("exec") && captured.contains("--json"))
        assertTrue("must be read-only sandbox", joined.contains("-s read-only") || (captured.contains("-s") && captured.contains("read-only")))
        assertTrue("approvals never", joined.contains("approval_policy=\"never\""))
        assertFalse("must NOT use danger-full-access", joined.contains("danger-full-access"))
        assertFalse("must NOT wire an MCP server", joined.contains("mcp_servers") || joined.contains("--mcp"))
    }

    @Test fun default_model_is_not_passed_as_flag() {
        libWith(CodexExecLibrarian.RunResult(0, """{"type":"item.completed","item":{"type":"agent_message","text":"ok"}}""", "", false), modelId = "default").ask("q")
        // "default" is a sentinel meaning "no -m override"; it must not become `-m default`.
        val i = captured.indexOf("-m")
        assertTrue(i == -1)
    }

    @Test fun prompt_preamble_redirects_away_from_mcp_tools() {
        val p = CodexExecLibrarian.buildPrompt("Call read_wiki_page then answer.", "Where is X?")
        assertTrue(p.contains("NO MCP tools"))
        assertTrue(p.contains("docs/llm-wiki/"))
        assertTrue(p.contains("Where is X?"))
    }
}
