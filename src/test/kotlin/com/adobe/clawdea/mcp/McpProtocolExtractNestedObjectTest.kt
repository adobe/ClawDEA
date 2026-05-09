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
package com.adobe.clawdea.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [McpProtocol.extractNestedObject] — the outer
 * `arguments` wrapper extractor. Previously its brace-depth scanner did not
 * track quoted strings, so any closing brace embedded in a scalar value of
 * `arguments` truncated the wrapper. Downstream parseSimpleObject then saw
 * only the fields before the stray brace and reported the rest as missing.
 */
class McpProtocolExtractNestedObjectTest {

    @Test
    fun `arguments wrapper survives closing brace inside string value`() {
        val body = "{\"method\":\"tools/call\",\"params\":{\"name\":\"propose_edit\",\"arguments\":" +
            "{\"file_path\":\"/x.kt\",\"old_string\":\"val x = }\",\"new_string\":\"val x = 1\"}}}"
        val args = McpProtocol.parseToolArguments(body)
        assertEquals("/x.kt", args["file_path"])
        assertEquals("val x = }", args["old_string"])
        assertEquals("val x = 1", args["new_string"])
    }

    @Test
    fun `arguments wrapper survives multiple braces inside string value`() {
        val body = "{\"method\":\"tools/call\",\"params\":{\"name\":\"propose_write\",\"arguments\":" +
            "{\"file_path\":\"/a.kt\",\"content\":\"fun a() { val b = { 1 } }\"}}}"
        val args = McpProtocol.parseToolArguments(body)
        assertEquals("/a.kt", args["file_path"])
        assertEquals("fun a() { val b = { 1 } }", args["content"])
    }

    @Test
    fun `arguments wrapper respects escaped quote before closing brace`() {
        // \"} inside a string — escaped quote does NOT end the string, so the
        // next } is still inside the string. The string-aware scanner must
        // honor the backslash escape.
        val body = "{\"method\":\"tools/call\",\"params\":{\"name\":\"propose_edit\",\"arguments\":" +
            "{\"file_path\":\"/x.kt\",\"old_string\":\"say \\\"hi\\\" }\",\"new_string\":\"bye\"}}}"
        val args = McpProtocol.parseToolArguments(body)
        assertEquals("/x.kt", args["file_path"])
        assertEquals("say \"hi\" }", args["old_string"])
        assertEquals("bye", args["new_string"])
    }
}
