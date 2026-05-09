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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatBrowserRendererTest {

    @Test fun `escapeForJs escapes backslashes`() {
        assertEquals("a\\\\b", ChatBrowserRenderer.escapeForJs("a\\b"))
    }

    @Test fun `escapeForJs escapes single quotes`() {
        assertEquals("it\\'s", ChatBrowserRenderer.escapeForJs("it's"))
    }

    @Test fun `escapeForJs escapes newlines`() {
        assertEquals("line1\\nline2", ChatBrowserRenderer.escapeForJs("line1\nline2"))
    }

    @Test fun `escapeForJs strips carriage returns`() {
        assertFalse(ChatBrowserRenderer.escapeForJs("a\rb").contains("\r"))
    }

    @Test fun `escapeForJs escapes closing script tags`() {
        val result = ChatBrowserRenderer.escapeForJs("<div></script><p>")
        assertTrue(result.contains("<\\/script>"))
        assertFalse(result.contains("</script>"))
    }

    @Test fun `escapeForJs handles empty string`() {
        assertEquals("", ChatBrowserRenderer.escapeForJs(""))
    }

    @Test fun `escapeForJs handles combined escapes`() {
        val input = "line1\nit's a\\path</script>"
        val result = ChatBrowserRenderer.escapeForJs(input)
        assertTrue(result.contains("\\n"))
        assertTrue(result.contains("\\'"))
        assertTrue(result.contains("\\\\"))
        assertTrue(result.contains("<\\/script>"))
    }
}
