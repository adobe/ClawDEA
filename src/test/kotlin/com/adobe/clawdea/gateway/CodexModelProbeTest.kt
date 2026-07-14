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
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexModelProbeTest {

    // Trimmed from a live ~/.codex/models_cache.json (fields codex adds are ignored).
    private val sample = """
        {
          "fetched_at": "2026-07-14T12:00:00Z",
          "etag": "abc",
          "client_version": "1.2.3",
          "models": [
            { "slug": "gpt-5.6-sol",   "display_name": "GPT-5.6-Sol",   "visibility": "list", "supported_in_api": true },
            { "slug": "gpt-5.6-terra", "display_name": "GPT-5.6-Terra", "visibility": "list", "supported_in_api": true },
            { "slug": "gpt-5.4-mini",  "display_name": "GPT-5.4-Mini",  "visibility": "list", "supported_in_api": true },
            { "slug": "codex-auto-review", "display_name": "Codex Auto Review", "visibility": "hide", "supported_in_api": true }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses listable models and preserves slug plus display name`() {
        val models = CodexModelProbe.parseModelsCache(sample)!!
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.4-mini"),
            models.map { it.id },
        )
        assertEquals("GPT-5.6-Sol", models.first().displayName)
        assertTrue(models.none { it.userAdded })
    }

    @Test
    fun `hidden models are excluded`() {
        val models = CodexModelProbe.parseModelsCache(sample)!!
        assertTrue(models.none { it.id == "codex-auto-review" })
    }

    @Test
    fun `falls back to slug when display name is missing`() {
        val json = """{ "models": [ { "slug": "gpt-x", "visibility": "list" } ] }"""
        val models = CodexModelProbe.parseModelsCache(json)!!
        assertEquals(1, models.size)
        assertEquals("gpt-x", models.first().id)
        assertEquals("gpt-x", models.first().displayName)
    }

    @Test
    fun `model with no visibility field is included`() {
        val json = """{ "models": [ { "slug": "gpt-x", "display_name": "GPT X" } ] }"""
        val models = CodexModelProbe.parseModelsCache(json)!!
        assertEquals(listOf("gpt-x"), models.map { it.id })
    }

    @Test
    fun `empty models array yields empty list not null`() {
        val models = CodexModelProbe.parseModelsCache("""{ "models": [] }""")
        assertEquals(emptyList<ModelEntry>(), models)
    }

    @Test
    fun `missing models key returns null`() {
        assertNull(CodexModelProbe.parseModelsCache("""{ "etag": "x" }"""))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(CodexModelProbe.parseModelsCache("not json"))
    }

    @Test
    fun `non-object root returns null`() {
        assertNull(CodexModelProbe.parseModelsCache("""[1, 2, 3]"""))
    }
}
