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
package com.adobe.clawdea.chat

import org.junit.Assert.*
import org.junit.Test

class MessageRendererTest {

    private val renderer = MessageRenderer()

    @Test
    fun `renders user message with user CSS class`() {
        val html = renderer.renderUserMessage("Hello Claude")
        assertTrue(html.contains("user-bubble"))
        assertTrue(html.contains("Hello Claude"))
    }

    @Test
    fun `renders assistant text with assistant CSS class`() {
        val html = renderer.renderAssistantText("Here is my answer")
        assertTrue(html.contains("assistant-bubble"))
        assertTrue(html.contains("Here is my answer"))
    }

    @Test
    fun `renders code blocks with pre tags`() {
        val html = renderer.renderAssistantText("```kotlin\nfun main() {}\n```")
        assertTrue(html.contains("<pre>"))
        assertTrue(html.contains("fun main()"))
    }

    @Test
    fun `renders inline code with code tags`() {
        val html = renderer.renderAssistantText("Use `println()` to print")
        assertTrue(html.contains("<code"))
        assertTrue(html.contains("println()"))
    }

    @Test
    fun `renders bold text`() {
        val html = renderer.renderAssistantText("This is **important**")
        assertTrue(html.contains("<strong>important</strong>"))
    }

    @Test
    fun `renders tool use card`() {
        val html = renderer.renderToolUse("Read", """{"file_path": "/tmp/test.kt"}""")
        assertTrue(html.contains("tool-block"))
        assertTrue(html.contains("Read"))
    }

    @Test
    fun `renders tool result card`() {
        val html = renderer.renderToolResult("File contents here...")
        assertTrue(html.contains("tool-result-header"))
        assertTrue(html.contains("tool-body-collapsible"))
        assertTrue(html.contains("File contents"))
    }

    @Test
    fun `renders error message with error CSS class`() {
        val html = renderer.renderError("Something went wrong")
        assertTrue(html.contains("error-block"))
        assertTrue(html.contains("Something went wrong"))
    }

    @Test
    fun `renders cost info`() {
        val html = renderer.renderCostInfo(null, null, 0.0523)
        assertTrue(html.contains("cost-info"))
        assertTrue(html.contains("0") && (html.contains("0523") || html.contains(",0523")))
    }

    @Test
    fun `escapes HTML entities in user input`() {
        val html = renderer.renderUserMessage("<script>alert('xss')</script>")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `renderCollapsedToolBlock shows tool name in compact format`() {
        val html = renderer.renderCollapsedToolBlock("TaskCreate", "toolu_123")
        assertTrue(html.contains("TaskCreate"))
        assertTrue(html.contains("tool-block-collapsed"))
        assertTrue(html.contains("toolu_123"))
    }

    @Test
    fun `renderSkillBadge returns badge HTML`() {
        val html = renderer.renderSkillBadge("brainstorming")
        assertTrue(html.contains("brainstorming"))
        assertTrue(html.contains("skill-badge"))
    }

    @Test
    fun `renders bullet list without br between items`() {
        val html = renderer.renderAssistantText("- first\n- second\n- third")
        assertTrue(html.contains("list-item"))
        assertFalse("Should not have <br> between list items",
            html.contains("</div><br><div class=\"list-item\">"))
        assertTrue(html.contains("first"))
        assertTrue(html.contains("second"))
        assertTrue(html.contains("third"))
    }

    @Test
    fun `renders markdown table as html table`() {
        val md = "| Name | Age |\n| --- | --- |\n| Alice | 30 |\n| Bob | 25 |"
        val html = renderer.renderAssistantText(md)
        assertTrue(html.contains("<table"))
        assertTrue(html.contains("<th>Name</th>"))
        assertTrue(html.contains("<td>Alice</td>"))
        assertTrue(html.contains("<td>25</td>"))
    }

    @Test
    fun `renderEditLink shows file name and status`() {
        val html = renderer.renderEditLink(
            filePath = "/src/main/kotlin/Foo.kt",
            toolUseId = "toolu_001",
            status = "Pending",
        )
        assertTrue(html.contains("Foo.kt"))
        assertTrue(html.contains("Pending"))
        assertTrue(html.contains("edit-link"))
        assertTrue(html.contains("toolu_001"))
    }

    @Test
    fun `renderEditLink escapes HTML in path`() {
        val html = renderer.renderEditLink(
            filePath = "/src/<script>.kt",
            toolUseId = "toolu_002",
            status = "Reviewing...",
        )
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `renderEditLink shows auto-accepted status`() {
        val html = renderer.renderEditLink(
            filePath = "/src/main/Foo.kt",
            toolUseId = "toolu_003",
            status = "Auto-accepted",
        )
        assertTrue(html.contains("Auto-accepted"))
        assertTrue(html.contains("edit-status-accepted"))
    }

    @Test
    fun `renderEditLink with showActions renders accept and reject buttons`() {
        val html = renderer.renderEditLink(
            filePath = "/src/main/Foo.kt",
            toolUseId = "toolu_010",
            status = "Applied",
            label = "Edit",
            showActions = true,
        )
        assertTrue(html.contains("edit-action-accept"))
        assertTrue(html.contains("edit-action-reject"))
        assertTrue(html.contains("toolu_010"))
    }

    @Test
    fun `renderEditLink without showActions renders status badge only`() {
        val html = renderer.renderEditLink(
            filePath = "/src/main/Foo.kt",
            toolUseId = "toolu_011",
            status = "Reviewing...",
        )
        assertTrue(html.contains("Reviewing..."))
        assertFalse(html.contains("edit-action-accept"))
    }

    @Test
    fun `renderEditLink with Write label`() {
        val html = renderer.renderEditLink(
            filePath = "/src/main/Bar.kt",
            toolUseId = "toolu_012",
            status = "Applied",
            label = "Write",
            showActions = true,
        )
        assertTrue(html.contains("Write"))
        assertTrue(html.contains("Bar.kt"))
    }

    @Test
    fun `renderToolUse uses data-action instead of onclick for stop button`() {
        val html = renderer.renderToolUse("Bash", """{"command":"ls"}""", "toolu_100")
        assertTrue(html.contains("""data-action="stop-tool""""))
        assertFalse("should not have inline onclick", html.contains("onclick"))
    }

    @Test
    fun `renderToolResult uses data-action instead of onclick for toggle`() {
        val html = renderer.renderToolResult("output text")
        assertTrue(html.contains("""data-action="toggle-tool-body""""))
        assertFalse("should not have inline onclick", html.contains("onclick"))
    }

    @Test
    fun `renderEditLink uses data-action for open-diff`() {
        val html = renderer.renderEditLink(
            filePath = "/src/main/Foo.kt",
            toolUseId = "toolu_020",
            status = "Reviewing...",
        )
        assertTrue(html.contains("""data-action="open-diff""""))
        assertTrue(html.contains("""data-tool-id="toolu_020""""))
        assertFalse("should not have inline onclick", html.contains("onclick"))
    }

    @Test
    fun `isCodeReference recognizes file with line`() {
        assertTrue(MessageRenderer.isCodeReference("ClawDEASettings.kt:84"))
    }

    @Test
    fun `isCodeReference recognizes file with line and column`() {
        assertTrue(MessageRenderer.isCodeReference("ChatPanel.kt:123:45"))
    }

    @Test
    fun `isCodeReference recognizes relative path`() {
        assertTrue(MessageRenderer.isCodeReference("src/main/kotlin/Foo.kt"))
    }

    @Test
    fun `isCodeReference recognizes absolute path`() {
        assertTrue(MessageRenderer.isCodeReference("/Users/me/project/Foo.kt"))
    }

    @Test
    fun `isCodeReference recognizes Class dot method`() {
        assertTrue(MessageRenderer.isCodeReference("TurnStateMachine.handle"))
    }

    @Test
    fun `isCodeReference recognizes Class dot method with parens`() {
        assertTrue(MessageRenderer.isCodeReference("TurnStateMachine.handle()"))
    }

    @Test
    fun `isCodeReference recognizes bare filename with known extension`() {
        assertTrue(MessageRenderer.isCodeReference("ChatPanel.kt"))
    }

    @Test
    fun `isCodeReference rejects plain word`() {
        assertFalse(MessageRenderer.isCodeReference("hello"))
    }

    @Test
    fun `isCodeReference rejects text with spaces`() {
        assertFalse(MessageRenderer.isCodeReference("hello world"))
    }

    @Test
    fun `isCodeReference rejects short text`() {
        assertFalse(MessageRenderer.isCodeReference("ab"))
    }

    @Test
    fun `inline code ref in assistant text gets navigate action`() {
        val html = renderer.renderAssistantText("See `ChatPanel.kt:84` for details")
        assertTrue(html.contains("""data-action="navigate""""))
        assertTrue(html.contains("""data-ref="ChatPanel.kt:84""""))
        assertTrue(html.contains("code-ref"))
    }

    @Test
    fun `all inline code is clickable`() {
        val html = renderer.renderAssistantText("Use `println()` to print")
        assertTrue(html.contains("code-ref"))
        assertTrue(html.contains("""data-action="navigate""""))
        assertTrue(html.contains("""data-ref="println()""""))
    }

    @Test
    fun `isCodeReference recognizes PascalCase class name`() {
        assertTrue(MessageRenderer.isCodeReference("InMemoryBookService"))
    }

    @Test
    fun `isCodeReference rejects single-segment capitalized word`() {
        assertFalse(MessageRenderer.isCodeReference("Service"))
    }

    @Test
    fun `ref link renders as navigable code ref`() {
        val html = renderer.renderAssistantText("See {[ref:BookService.listAll|listAll]} for details")
        assertTrue(html.contains("""data-action="navigate""""))
        assertTrue(html.contains("""data-ref="BookService.listAll""""))
        assertTrue(html.contains(">listAll</code>"))
        assertFalse(html.contains("{[ref:"))
    }

    @Test
    fun `ref link with file path`() {
        val html = renderer.renderAssistantText("Check {[ref:ChatPanel.kt:84|ChatPanel.kt:84]}")
        assertTrue(html.contains("""data-ref="ChatPanel.kt:84""""))
        assertTrue(html.contains(">ChatPanel.kt:84</code>"))
    }

    @Test
    fun `ref link with line range preserves range in data-ref`() {
        val html = renderer.renderAssistantText("Look at {[ref:ChatPanel.kt:84-120|ChatPanel.kt:84-120]}")
        assertTrue(html.contains("""data-ref="ChatPanel.kt:84-120""""))
        assertTrue(html.contains(">ChatPanel.kt:84-120</code>"))
    }

    @Test
    fun `ref link with parens in label`() {
        val html = renderer.renderAssistantText("call {[ref:BridgeForwardHandler.execute|BridgeForwardHandler.execute()]} runs")
        assertTrue(html.contains("""data-ref="BridgeForwardHandler.execute""""))
        assertTrue(html.contains(">BridgeForwardHandler.execute&#40;&#41;</code>"))
        assertFalse("no stray delimiters", html.contains("{[ref:"))
    }

    @Test
    fun `ref link with FQCN query`() {
        val html = renderer.renderAssistantText("{[ref:com.adobe.clawdea.cli.CliProcess.start|CliProcess.start()]}")
        assertTrue(html.contains("""data-ref="com.adobe.clawdea.cli.CliProcess.start""""))
        assertTrue(html.contains(">CliProcess.start&#40;&#41;</code>"))
    }

    @Test
    fun `ref link without pipe is left as-is`() {
        val html = renderer.renderAssistantText("broken {[ref:nopipe]} here")
        assertTrue("malformed ref should pass through", html.contains("{[ref:nopipe]}"))
    }

    @Test
    fun `ref link without closing delimiter is left as-is`() {
        val html = renderer.renderAssistantText("broken {[ref:query|label here")
        assertTrue("unclosed ref should pass through", html.contains("{[ref:"))
    }

    @Test
    fun `renderFileLink title shows relative path when file is under project`() {
        val r = MessageRenderer(projectBasePath = "/home/user/project")
        val html = r.renderFileLink("/home/user/project/src/main/Foo.kt", "toolu_050")
        assertTrue(html.contains("""title="src/main/Foo.kt""""))
        assertTrue("data-file-path should remain absolute",
            html.contains("""data-file-path="/home/user/project/src/main/Foo.kt""""))
    }

    @Test
    fun `renderFileLink title shows absolute path when file is outside project`() {
        val r = MessageRenderer(projectBasePath = "/home/user/project")
        val html = r.renderFileLink("/tmp/other/Bar.kt", "toolu_051")
        assertTrue(html.contains("""title="/tmp/other/Bar.kt""""))
    }

    @Test
    fun `renderEditLink title shows relative path when file is under project`() {
        val r = MessageRenderer(projectBasePath = "/home/user/project")
        val html = r.renderEditLink(
            filePath = "/home/user/project/src/main/Foo.kt",
            toolUseId = "toolu_052",
            status = "Pending",
        )
        assertTrue(html.contains("""title="src/main/Foo.kt""""))
    }

    @Test
    fun `renderFileLink title shows absolute path when no project base path`() {
        val r = MessageRenderer()
        val html = r.renderFileLink("/home/user/project/src/main/Foo.kt", "toolu_053")
        assertTrue(html.contains("""title="/home/user/project/src/main/Foo.kt""""))
    }

    @Test
    fun `renderFileLink extracts filename from Windows path`() {
        val html = renderer.renderFileLink("""C:\Users\dev\project\src\Foo.kt""", "toolu_060")
        assertTrue("Should show just filename, not full path", html.contains(">Foo.kt<"))
        assertFalse("Should not show drive letter in link text", html.contains(">C:\\"))
    }

    @Test
    fun `renderEditLink extracts filename from Windows path`() {
        val html = renderer.renderEditLink(
            filePath = """C:\Users\dev\project\src\Bar.kt""",
            toolUseId = "toolu_061",
            status = "Pending",
        )
        assertTrue("Should show just filename", html.contains(">Bar.kt<"))
        assertFalse("Should not show drive letter in link text", html.contains(">C:\\"))
    }

    @Test
    fun `renderFileLink tooltip shows relative path for Windows project`() {
        val r = MessageRenderer(projectBasePath = """C:\Users\dev\project""")
        val html = r.renderFileLink("""C:\Users\dev\project\src\main\Foo.kt""", "toolu_062")
        assertTrue(html.contains("""title="src/main/Foo.kt""""))
    }

    @Test
    fun `renderEditLink with showActions uses data-action for accept and reject`() {
        val html = renderer.renderEditLink(
            filePath = "/src/main/Foo.kt",
            toolUseId = "toolu_021",
            status = "Applied",
            showActions = true,
        )
        assertTrue(html.contains("""data-action="edit-accept""""))
        assertTrue(html.contains("""data-action="edit-reject""""))
        assertTrue(html.contains("""data-tool-id="toolu_021""""))
        assertFalse("should not have inline onclick", html.contains("onclick"))
    }

    @Test
    fun `renderToolUseFromHistory renders Edit as accepted edit link with file name`() {
        val html = renderer.renderToolUseFromHistory(
            toolName = "Edit",
            input = """{"file_path":"/src/main/Foo.kt","old_string":"a","new_string":"b"}""",
            toolUseId = "toolu_h1",
            resultContent = "The file /src/main/Foo.kt has been updated.",
        )
        assertTrue(html.contains("edit-link"))
        assertTrue("Should display Accepted status", html.contains("Accepted"))
        assertTrue("Should show file name", html.contains(">Foo.kt<"))
        assertFalse("Should not show in-flight actions", html.contains("edit-action-accept"))
        assertFalse("Result content rendered separately is suppressed for edits", html.contains("has been updated"))
    }

    @Test
    fun `renderToolUseFromHistory renders Write with Write label`() {
        val html = renderer.renderToolUseFromHistory(
            toolName = "Write",
            input = """{"file_path":"/src/main/Bar.kt","content":"package x"}""",
            toolUseId = "toolu_h2",
        )
        assertTrue(html.contains("edit-link"))
        assertTrue(html.contains(">Write<"))
        assertTrue(html.contains(">Bar.kt<"))
        assertTrue(html.contains("Accepted"))
    }

    @Test
    fun `renderToolUseFromHistory marks errored edit as Failed`() {
        val html = renderer.renderToolUseFromHistory(
            toolName = "Edit",
            input = """{"file_path":"/src/main/Foo.kt","old_string":"a","new_string":"b"}""",
            toolUseId = "toolu_h3",
            resultContent = "file not found",
            isError = true,
        )
        assertTrue(html.contains("Failed"))
        assertFalse(html.contains("Accepted"))
    }

    @Test
    fun `renderToolUseFromHistory renders Read as file link with no inlined result`() {
        val html = renderer.renderToolUseFromHistory(
            toolName = "Read",
            input = """{"file_path":"/src/main/Foo.kt"}""",
            toolUseId = "toolu_h4",
            resultContent = "file contents here",
        )
        assertTrue(html.contains("edit-link"))
        assertTrue(html.contains(">Read<"))
        assertTrue(html.contains(">Foo.kt<"))
        assertFalse("Read should not surface its (often huge) file body", html.contains("file contents here"))
    }

    @Test
    fun `renderToolUseFromHistory suppresses AskUserQuestion entirely`() {
        val html = renderer.renderToolUseFromHistory(
            toolName = "AskUserQuestion",
            input = """{"question":"pick one","options":["a","b"]}""",
            toolUseId = "toolu_h5",
            resultContent = "answer: a",
        )
        assertEquals("", html)
    }

    @Test
    fun `renderToolUseFromHistory renders TodoWrite as collapsed block`() {
        val html = renderer.renderToolUseFromHistory(
            toolName = "TodoWrite",
            input = """{"todos":[{"id":"1","content":"thing","status":"pending"}]}""",
            toolUseId = "toolu_h6",
        )
        assertTrue(html.contains("tool-block-collapsed"))
        assertTrue(html.contains("TodoWrite"))
    }

    @Test
    fun `renderToolUseFromHistory inlines result for generic tool inside the tool block`() {
        val html = renderer.renderToolUseFromHistory(
            toolName = "Bash",
            input = """{"command":"ls","description":"List files"}""",
            toolUseId = "toolu_h7",
            resultContent = "foo.txt\nbar.txt",
        )
        assertTrue(html.contains("tool-block"))
        assertTrue(html.contains("List files"))
        // Result HTML is appended INSIDE the tool block so the live "Output"
        // toggle and the replayed view look the same.
        assertTrue(html.contains("tool-result-header"))
        assertTrue(html.contains("foo.txt"))
        val resultIdx = html.indexOf("tool-result-header")
        val closingBlockIdx = html.lastIndexOf("</div>")
        assertTrue("Result HTML should sit inside the outer tool-block container", resultIdx < closingBlockIdx)
    }

    @Test
    fun `renderToolUseFromHistory omits stop button since the call already finished`() {
        val html = renderer.renderToolUseFromHistory(
            toolName = "Bash",
            input = """{"command":"ls"}""",
            toolUseId = "toolu_h8",
        )
        assertFalse(html.contains("tool-stop-btn"))
    }

    // ---- renderToolUseEvent in Live mode: same routing the live stream uses ----

    @Test
    fun `renderToolUseEvent Live with auto-accept renders edit as Auto-accepted`() {
        val html = renderer.renderToolUseEvent(
            toolName = "Edit",
            input = """{"file_path":"/src/Foo.kt","old_string":"a","new_string":"b"}""",
            toolUseId = "toolu_L1",
            mode = ToolMode.Live(autoAcceptEdits = true),
        )
        assertTrue(html.contains("Auto-accepted"))
        assertTrue(html.contains("edit-status-accepted"))
        assertFalse("auto-accept should not render in-flight actions", html.contains("edit-action-accept"))
    }

    @Test
    fun `renderToolUseEvent Live with manual review renders propose tool as Reviewing`() {
        val html = renderer.renderToolUseEvent(
            toolName = "propose_edit",
            input = """{"file_path":"/src/Foo.kt","old_string":"a","new_string":"b"}""",
            toolUseId = "toolu_L2",
            mode = ToolMode.Live(autoAcceptEdits = false),
        )
        assertTrue(html.contains("Reviewing..."))
        assertFalse(html.contains("edit-action-accept"))
    }

    @Test
    fun `renderToolUseEvent Live with manual review renders built-in Edit with inline actions`() {
        // Layer 2 fallback: CC used the built-in Edit/Write rather than the
        // MCP propose tools, so the live UI shows in-line accept/reject buttons
        // instead of a status badge. The "Applied" status passed to
        // renderEditLink is intentionally suppressed when showActions=true.
        val html = renderer.renderToolUseEvent(
            toolName = "Edit",
            input = """{"file_path":"/src/Foo.kt","old_string":"a","new_string":"b"}""",
            toolUseId = "toolu_L3",
            mode = ToolMode.Live(autoAcceptEdits = false),
        )
        assertTrue(html.contains("edit-action-accept"))
        assertTrue(html.contains("edit-action-reject"))
        assertTrue(html.contains(">Foo.kt<"))
    }

    @Test
    fun `renderToolUseEvent Live for task tool emits no HTML (task widget owns display)`() {
        val html = renderer.renderToolUseEvent(
            toolName = "TodoWrite",
            input = """{"todos":[]}""",
            toolUseId = "toolu_L4",
            mode = ToolMode.Live(autoAcceptEdits = false),
        )
        assertEquals("", html)
    }

    @Test
    fun `renderToolUseEvent Live for generic tool keeps stop button`() {
        val html = renderer.renderToolUseEvent(
            toolName = "Bash",
            input = """{"command":"ls"}""",
            toolUseId = "toolu_L5",
            mode = ToolMode.Live(autoAcceptEdits = false),
        )
        assertTrue(html.contains("tool-stop-btn"))
        assertTrue(html.contains("""data-tool-id="toolu_L5""""))
    }

    @Test
    fun `renderToolUseEvent Live for generic tool renders expanded with toggle handler`() {
        val html = renderer.renderToolUseEvent(
            toolName = "Bash",
            input = """{"command":"ls"}""",
            toolUseId = "toolu_L5b",
            mode = ToolMode.Live(autoAcceptEdits = false),
        )
        assertTrue("live block should start expanded", html.contains("tool-block expanded"))
        assertTrue("header should dispatch toggle action", html.contains("""data-action="toggle-tool-block""""))
    }

    @Test
    fun `renderToolUseEvent Replay for generic tool renders collapsed`() {
        val html = renderer.renderToolUseEvent(
            toolName = "Bash",
            input = """{"command":"ls"}""",
            toolUseId = "toolu_L5c",
            mode = ToolMode.Replay(resultContent = "files...", isError = false),
        )
        assertTrue(html.contains("tool-block"))
        assertFalse("replayed block should be collapsed (no expanded class)", html.contains("tool-block expanded"))
    }

    @Test
    fun `renderToolUseEvent Live for Read renders file link`() {
        val html = renderer.renderToolUseEvent(
            toolName = "Read",
            input = """{"file_path":"/src/Foo.kt"}""",
            toolUseId = "toolu_L6",
            mode = ToolMode.Live(autoAcceptEdits = false),
        )
        assertTrue(html.contains("edit-link"))
        assertTrue(html.contains(">Foo.kt<"))
    }

    @Test
    fun `renderToolUseEvent Live suppresses AskUserQuestion`() {
        val html = renderer.renderToolUseEvent(
            toolName = "AskUserQuestion",
            input = """{"question":"pick","options":[]}""",
            toolUseId = "toolu_L7",
            mode = ToolMode.Live(autoAcceptEdits = false),
        )
        assertEquals("", html)
    }

    // ---- Wiki MCP tool / wiki-dir edit icons ----

    private fun rendererWithWiki(): MessageRenderer = MessageRenderer(
        projectBasePath = "/home/user/project",
        wikiDirResolver = { java.nio.file.Path.of("/home/user/project/.claude/wiki") },
    )

    @Test
    fun `renderToolUse uses wiki book icon for read_wiki_page`() {
        val html = rendererWithWiki().renderToolUse("read_wiki_page", "{}", "toolu_W1")
        assertTrue("Should use 📚 icon for wiki read tool", html.contains("📚"))
        assertFalse("Should not fall through to default ⚙ icon", html.contains("⚙"))
    }

    @Test
    fun `renderToolUse uses wiki book icon for search_wiki`() {
        val html = rendererWithWiki().renderToolUse("search_wiki", "{}", "toolu_W2")
        assertTrue(html.contains("📚"))
        assertFalse(html.contains("⚙"))
    }

    @Test
    fun `renderToolUse uses wiki book icon for read_sibling_wiki`() {
        val html = rendererWithWiki().renderToolUse("read_sibling_wiki", "{}", "toolu_W3")
        assertTrue(html.contains("📚"))
    }

    @Test
    fun `renderToolUse uses wiki book icon for MCP-namespaced read_wiki_page`() {
        val html = rendererWithWiki().renderToolUse(
            "mcp__clawdea-intellij__read_wiki_page",
            "{}",
            "toolu_W4",
        )
        assertTrue("MCP-namespaced wiki read tool should still get 📚 icon", html.contains("📚"))
    }

    @Test
    fun `renderToolUse uses wiki write icon for record_wiki_suggestion`() {
        val html = rendererWithWiki().renderToolUse("record_wiki_suggestion", "{}", "toolu_W5")
        assertTrue("Should use 📝 icon for wiki write tool", html.contains("📝"))
    }

    @Test
    fun `renderToolUse uses wiki write icon for Edit when file_path is under wiki dir`() {
        val html = rendererWithWiki().renderToolUse(
            "Edit",
            """{"file_path": "/home/user/project/.claude/wiki/concepts/foo.md"}""",
            "toolu_W6",
        )
        assertTrue("Edit under wiki dir should use 📝 icon", html.contains("📝"))
        assertFalse("Should not also use the generic pencil ✏ icon", html.contains("✏"))
    }

    @Test
    fun `renderToolUse uses wiki write icon for Write when file_path is under wiki dir`() {
        val html = rendererWithWiki().renderToolUse(
            "Write",
            """{"file_path": "/home/user/project/.claude/wiki/concepts/bar.md"}""",
            "toolu_W7",
        )
        assertTrue(html.contains("📝"))
    }

    @Test
    fun `renderToolUse uses wiki write icon for propose_edit when file_path is under wiki dir`() {
        val html = rendererWithWiki().renderToolUse(
            "propose_edit",
            """{"file_path": "/home/user/project/.claude/wiki/concepts/baz.md"}""",
            "toolu_W8",
        )
        assertTrue(html.contains("📝"))
    }

    @Test
    fun `renderToolUse uses wiki write icon for propose_write when file_path is under wiki dir`() {
        val html = rendererWithWiki().renderToolUse(
            "propose_write",
            """{"file_path": "/home/user/project/.claude/wiki/concepts/qux.md"}""",
            "toolu_W9",
        )
        assertTrue(html.contains("📝"))
    }

    @Test
    fun `renderToolUse keeps pencil icon for Edit when file_path is outside wiki dir`() {
        val html = rendererWithWiki().renderToolUse(
            "Edit",
            """{"file_path": "/home/user/project/src/main/Foo.kt"}""",
            "toolu_W10",
        )
        assertTrue("Non-wiki edits should keep the ✏ icon", html.contains("✏"))
        assertFalse("Should not use the wiki 📝 icon for non-wiki edits", html.contains("📝"))
    }

    @Test
    fun `renderFileLink uses wiki book icon for Read of file under wiki dir`() {
        val html = rendererWithWiki().renderFileLink(
            "/home/user/project/.claude/wiki/concepts/foo.md",
            "toolu_W11",
        )
        assertTrue("Read of wiki file should use 📚 (HTML-entity form)", html.contains("&#x1F4DA;"))
        assertFalse("Should not also use the default page icon", html.contains("&#x1F4C4;"))
    }

    @Test
    fun `renderFileLink keeps default page icon for Read of file outside wiki dir`() {
        val html = rendererWithWiki().renderFileLink(
            "/home/user/project/src/main/Foo.kt",
            "toolu_W12",
        )
        assertTrue("Non-wiki read should keep the default page icon", html.contains("&#x1F4C4;"))
        assertFalse(html.contains("&#x1F4DA;"))
    }

    @Test
    fun `renderFileLink uses wiki write icon for Edit of file under wiki dir`() {
        val html = rendererWithWiki().renderFileLink(
            "/home/user/project/.claude/wiki/concepts/foo.md",
            "toolu_W13",
            label = "Edit",
        )
        assertTrue("Edit of wiki file should use 📝 (HTML-entity form)", html.contains("&#x1F4DD;"))
        assertFalse("Should not also use the default pencil icon", html.contains("&#x270F;"))
    }
}
