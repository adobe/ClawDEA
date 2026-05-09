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
        val html = renderer.renderCostInfo(0.0523)
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

}
