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
package com.adobe.clawdea.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnthropicModelProbeTest {

    @Test
    fun `parseModelsJson returns empty list for empty data array`() {
        val json = """{"data": []}"""
        assertEquals(emptyList<ModelEntry>(), AnthropicModelProbe.parseModelsJson(json))
    }

    @Test
    fun `parseModelsJson extracts id and display_name`() {
        val json = """
            {"data":[
                {"id":"claude-opus-4-7","display_name":"Claude Opus 4.7","type":"model"},
                {"id":"claude-sonnet-4-6","display_name":"Claude Sonnet 4.6","type":"model"}
            ]}
        """.trimIndent()
        val result = AnthropicModelProbe.parseModelsJson(json)!!
        assertEquals(2, result.size)
        assertEquals("claude-opus-4-7", result[0].id)
        assertEquals("Claude Opus 4.7", result[0].displayName)
        assertEquals(false, result[0].userAdded)
        assertEquals("claude-sonnet-4-6", result[1].id)
    }

    @Test
    fun `parseModelsJson returns null for malformed JSON`() {
        assertNull(AnthropicModelProbe.parseModelsJson("not json"))
    }

    @Test
    fun `parseModelsJson returns null when data field is missing`() {
        assertNull(AnthropicModelProbe.parseModelsJson("""{"models":[]}"""))
    }

    @Test
    fun `parseModelsJson skips entries missing id or display_name`() {
        val json = """
            {"data":[
                {"id":"ok","display_name":"OK"},
                {"display_name":"No id"},
                {"id":"no-display"},
                {"id":"also-ok","display_name":"Also OK"}
            ]}
        """.trimIndent()
        val result = AnthropicModelProbe.parseModelsJson(json)!!
        assertEquals(listOf("ok", "also-ok"), result.map { it.id })
    }
}
