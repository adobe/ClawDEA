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
    fun `buildBridgeScripts includes all bridge functions`() {
        val template = ChatHtmlTemplate()
        val js = template.buildBridgeScripts(
            abortJs = "ABORT_JS",
            openDiffJs = "DIFF_JS",
            editActionJs = "EDIT_JS",
            healthJs = "HEALTH_JS",
            openFileJs = "OPEN_FILE_JS",
            navigateJs = "NAVIGATE_JS",
            permissionDecisionJs = "PERMISSION_JS",
            driftActionJs = "DRIFT_JS",
        )
        assertContains(js, "ABORT_JS")
        assertContains(js, "DIFF_JS")
        assertContains(js, "EDIT_JS")
        assertContains(js, "HEALTH_JS")
        assertContains(js, "OPEN_FILE_JS")
        assertContains(js, "NAVIGATE_JS")
        assertContains(js, "PERMISSION_JS")
        assertContains(js, "DRIFT_JS")
        assertContains(js, "window.bridgeStopTool")
        assertContains(js, "window.bridgeOpenDiff")
        assertContains(js, "window.bridgeEditAction")
        assertContains(js, "window.bridgeHealthPing")
        assertContains(js, "window.bridgeOpenFile")
        assertContains(js, "window.bridgeNavigate")
        assertContains(js, "window.bridgePermissionDecision")
        assertContains(js, "window.bridgeDriftAction")
        assertContains(js, "permission-allow")
        assertContains(js, "permission-deny")
        assertContains(js, "drift-action")
    }
}
