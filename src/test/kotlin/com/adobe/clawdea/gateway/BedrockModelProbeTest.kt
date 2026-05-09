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

class BedrockModelProbeTest {

    @Test
    fun `parseModelsJson extracts id and inferenceProfileName`() {
        val json = """
            {"inferenceProfileSummaries":[
                {"inferenceProfileId":"us.anthropic.claude-opus-4-7","inferenceProfileName":"US Anthropic Claude Opus 4.7","status":"ACTIVE"},
                {"inferenceProfileId":"us.anthropic.claude-sonnet-4-6","inferenceProfileName":"US Anthropic Claude Sonnet 4.6","status":"ACTIVE"}
            ]}
        """.trimIndent()
        val result = BedrockModelProbe.parseModelsJson(json)!!
        assertEquals(2, result.size)
        assertEquals("us.anthropic.claude-opus-4-7", result[0].id)
        assertEquals("US Anthropic Claude Opus 4.7", result[0].displayName)
        assertEquals(false, result[0].userAdded)
    }

    @Test
    fun `parseModelsJson skips non-ACTIVE profiles`() {
        val json = """
            {"inferenceProfileSummaries":[
                {"inferenceProfileId":"us.anthropic.claude-opus-4-7","inferenceProfileName":"Keep","status":"ACTIVE"},
                {"inferenceProfileId":"us.anthropic.legacy","inferenceProfileName":"Drop","status":"LEGACY"}
            ]}
        """.trimIndent()
        val result = BedrockModelProbe.parseModelsJson(json)!!
        assertEquals(listOf("us.anthropic.claude-opus-4-7"), result.map { it.id })
    }

    @Test
    fun `parseModelsJson keeps only anthropic profiles`() {
        val json = """
            {"inferenceProfileSummaries":[
                {"inferenceProfileId":"us.anthropic.claude-opus-4-7","inferenceProfileName":"Opus","status":"ACTIVE"},
                {"inferenceProfileId":"us.meta.llama3-70b","inferenceProfileName":"Llama","status":"ACTIVE"},
                {"inferenceProfileId":"us.amazon.nova-premier-v1:0","inferenceProfileName":"Nova","status":"ACTIVE"}
            ]}
        """.trimIndent()
        val result = BedrockModelProbe.parseModelsJson(json)!!
        assertEquals(listOf("us.anthropic.claude-opus-4-7"), result.map { it.id })
    }

    @Test
    fun `parseModelsJson accepts global dot anthropic prefixed ids`() {
        val json = """
            {"inferenceProfileSummaries":[
                {"inferenceProfileId":"global.anthropic.claude-opus-4-7","inferenceProfileName":"Global Opus","status":"ACTIVE"}
            ]}
        """.trimIndent()
        val result = BedrockModelProbe.parseModelsJson(json)!!
        assertEquals(listOf("global.anthropic.claude-opus-4-7"), result.map { it.id })
    }

    @Test
    fun `parseModelsJson falls back to id when inferenceProfileName is absent`() {
        val json = """
            {"inferenceProfileSummaries":[
                {"inferenceProfileId":"us.anthropic.claude-haiku-4-5","status":"ACTIVE"}
            ]}
        """.trimIndent()
        val result = BedrockModelProbe.parseModelsJson(json)!!
        assertEquals("us.anthropic.claude-haiku-4-5", result[0].displayName)
    }

    @Test
    fun `parseModelsJson returns null for malformed JSON`() {
        assertNull(BedrockModelProbe.parseModelsJson("not json"))
    }

    @Test
    fun `parseModelsJson returns null when inferenceProfileSummaries field is missing`() {
        assertNull(BedrockModelProbe.parseModelsJson("""{"data":[]}"""))
    }
}
