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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiSubscriptionAuthProviderTest {

    @Test
    fun `id is openai-subscription`() {
        assertEquals("openai-subscription", OpenAiSubscriptionAuthProvider(isSignedIn = true).id)
    }

    @Test
    fun `isConfigured reflects signed-in state`() {
        assertTrue(OpenAiSubscriptionAuthProvider(isSignedIn = true).isConfigured())
        assertFalse(OpenAiSubscriptionAuthProvider(isSignedIn = false).isConfigured())
    }

    @Test
    fun `applyToEnvironment removes OPENAI_API_KEY so the key cannot shadow ChatGPT sign-in`() {
        val env = mutableMapOf("OPENAI_API_KEY" to "sk-should-be-removed", "PATH" to "/usr/bin")
        OpenAiSubscriptionAuthProvider(isSignedIn = true).applyToEnvironment(env)
        assertNull(env["OPENAI_API_KEY"])
        assertEquals("/usr/bin", env["PATH"])
    }

    @Test
    fun `validate is valid only when signed in`() {
        assertTrue(OpenAiSubscriptionAuthProvider(isSignedIn = true).validate().valid)
        val invalid = OpenAiSubscriptionAuthProvider(isSignedIn = false).validate()
        assertFalse(invalid.valid)
        assertTrue(invalid.message!!.contains("/login"))
    }

    @Test
    fun `testConnection succeeds only when signed in`() {
        assertTrue(OpenAiSubscriptionAuthProvider(isSignedIn = true).testConnection().success)
        assertFalse(OpenAiSubscriptionAuthProvider(isSignedIn = false).testConnection().success)
    }
}
