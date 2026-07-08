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

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ChatHtmlTemplateTest {

    @Test
    fun `buildPage returns valid HTML with empty initial content`() {
        val template = ChatHtmlTemplate()
        val html = template.buildPage()
        assertContains(html, "<div id=\"messages\"></div>")
        assertContains(html, "<!DOCTYPE html>")
    }

    @Test
    fun `buildPage substitutes initial content`() {
        val template = ChatHtmlTemplate()
        val html = template.buildPage("<div>hello</div>")
        assertContains(html, "<div id=\"messages\"><div>hello</div></div>")
    }

    @Test
    fun `thinking indicator exposes pause and stop controls`() {
        val template = ChatHtmlTemplate()
        val html = template.buildPage()

        assertContains(html, """data-action="turn-pause"""")
        assertContains(html, """aria-label="Pause response"""")
        assertContains(html, "thinking-control-icon pause")
        assertContains(html, "setTurnControlButton")
        assertContains(html, """data-action', 'turn-stop'""")
        assertContains(html, "thinking-control-icon stop")
    }

    @Test
    fun `page has a pinned bottom dock for active agents and the thinking indicator`() {
        val template = ChatHtmlTemplate()
        val html = template.buildPage()

        // Dock structure: a sticky container holding the active-agents stack
        // and the thinking-indicator slot.
        assertContains(html, "id=\"dock\"")
        assertContains(html, "id=\"active-agents\"")
        assertContains(html, "id=\"thinking-slot\"")
        assertContains(html, "#dock {")
        assertContains(html, "position: sticky;")
    }

    @Test
    fun `active sub-agent cards start pinned in the dock`() {
        val template = ChatHtmlTemplate()
        val html = template.buildPage()

        // startActiveAgent appends the card into #active-agents (the pinned dock)
        // rather than the scrolling message flow.
        assertContains(html, "function startActiveAgent(html)")
        assertContains(html, "getElementById('active-agents')")
    }

    @Test
    fun `finished sub-agent card is released from the dock back into the flow`() {
        val template = ChatHtmlTemplate()
        val html = template.buildPage()

        // finalizeSubAgent collapses the card and moves it from the dock into
        // #messages so it can scroll away only once finished + collapsed.
        assertContains(html, "function finalizeSubAgent(parentId, summaryHtml)")
        assertContains(html, "block.classList.remove('expanded')")
        assertContains(html, "messages.appendChild(block)")
    }

    @Test
    fun `thinking indicator is rendered into the pinned slot not the message flow`() {
        val template = ChatHtmlTemplate()
        val html = template.buildPage()

        assertContains(html, "function showThinking()")
        assertContains(html, "getElementById('thinking-slot')")
    }

    @Test
    fun `activity indicator can be re-asserted and self-heals during long turns`() {
        val template = ChatHtmlTemplate()
        val html = template.buildPage()

        // pokeThinking recreates the hint if a mid-turn path removed it.
        assertContains(html, "function pokeThinking()")
        assertContains(html, "{ showThinking(); return; }")
        // A repaint heartbeat keeps JCEF compositing the dots during event-sparse
        // stretches, and is cleaned up when the indicator is hidden.
        assertContains(html, "function startThinkingHeartbeat()")
        assertContains(html, "function stopThinkingHeartbeat()")
        assertContains(html, "stopThinkingHeartbeat();")
    }

    @Test
    fun `buildBridgeScripts includes all bridge functions`() {
        val template = ChatHtmlTemplate()
        val js = template.buildBridgeScripts(
            abortJs = "ABORT_JS",
            turnControlJs = "TURN_CONTROL_JS",
            openDiffJs = "DIFF_JS",
            editActionJs = "EDIT_JS",
            healthJs = "HEALTH_JS",
            openFileJs = "OPEN_FILE_JS",
            navigateJs = "NAVIGATE_JS",
            permissionDecisionJs = "PERMISSION_JS",
            driftActionJs = "DRIFT_JS",
            runSlashCommandJs = "RUN_SLASH_JS",
            wikiGitStateActionJs = "WGS_JS",
        )
        assertContains(js, "ABORT_JS")
        assertContains(js, "TURN_CONTROL_JS")
        assertContains(js, "DIFF_JS")
        assertContains(js, "EDIT_JS")
        assertContains(js, "HEALTH_JS")
        assertContains(js, "OPEN_FILE_JS")
        assertContains(js, "NAVIGATE_JS")
        assertContains(js, "PERMISSION_JS")
        assertContains(js, "DRIFT_JS")
        assertContains(js, "RUN_SLASH_JS")
        assertContains(js, "WGS_JS")
        assertContains(js, "window.bridgeStopTool")
        assertContains(js, "window.bridgeTurnControl")
        assertContains(js, "window.bridgeOpenDiff")
        assertContains(js, "window.bridgeEditAction")
        assertContains(js, "window.bridgeHealthPing")
        assertContains(js, "window.bridgeOpenFile")
        assertContains(js, "window.bridgeNavigate")
        assertContains(js, "window.bridgePermissionDecision")
        assertContains(js, "window.bridgeDriftAction")
        assertContains(js, "window.bridgeRunSlashCommand")
        assertContains(js, "window.bridgeWikiGitStateAction")
        assertContains(js, "permission-allow")
        assertContains(js, "permission-deny")
        assertContains(js, "turn-pause")
        assertContains(js, "turn-stop")
        assertContains(js, "drift-action")
        assertContains(js, "run-slash-command")
        assertContains(js, "wiki-git-state-action")
    }
}
