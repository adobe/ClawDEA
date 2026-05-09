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
package com.adobe.clawdea.auth

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class VertexAuthProviderTest {
    @Test fun `isConfigured true when region set`() { assertTrue(VertexAuthProvider("us-central1", "").isConfigured()) }
    @Test fun `isConfigured false when both empty`() { assertFalse(VertexAuthProvider("", "").isConfigured()) }
    @Test fun `applyToEnvironment sets vars`() {
        val env = mutableMapOf<String, String>()
        VertexAuthProvider("europe-west1", "proj-123").applyToEnvironment(env)
        assertEquals("1", env["CLAUDE_CODE_USE_VERTEX"])
        assertEquals("europe-west1", env["CLOUD_ML_REGION"])
        assertEquals("proj-123", env["ANTHROPIC_VERTEX_PROJECT_ID"])
    }
    @Test fun `applyToEnvironment skips blanks`() {
        val env = mutableMapOf<String, String>()
        VertexAuthProvider("", "").applyToEnvironment(env)
        assertEquals("1", env["CLAUDE_CODE_USE_VERTEX"])
        assertFalse(env.containsKey("CLOUD_ML_REGION"))
    }
    @Test fun `validate fails when empty`() { assertFalse(VertexAuthProvider("", "").validate().valid) }
    @Test fun `validate succeeds when configured`() { assertTrue(VertexAuthProvider("us-central1", "").validate().valid) }
    @Test fun `testConnection fails when not configured`() {
        val r = VertexAuthProvider("", "").testConnection()
        assertFalse(r.success)
        assertTrue(r.message.contains("not configured"))
    }
}
