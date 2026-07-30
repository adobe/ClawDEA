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
package com.adobe.clawdea.settings.tabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against Settings→Apply deleting stored credentials.
 *
 * ClawDEASettings.getSecret returns "" when called on the EDT before the secret cache is warm (it
 * refuses to block the EDT on PasswordSafe). Settings runs on the EDT, so a password field can be
 * blank while a real secret is stored — and setSecret maps blank to a PasswordSafe delete. Persist
 * only fields the user actually changed relative to what loadFrom put there.
 */
class ProvidersTabSecretPersistenceTest {

    @Test
    fun `an untouched blank field is never persisted`() {
        // loadFrom got "" back from the cold-cache EDT read; the user typed nothing.
        assertFalse(ProvidersTab.shouldPersistSecret(fieldValue = "", loadedValue = ""))
    }

    @Test
    fun `an untouched populated field is never persisted`() {
        assertFalse(ProvidersTab.shouldPersistSecret(fieldValue = "sk-stored", loadedValue = "sk-stored"))
    }

    @Test
    fun `a newly typed key is persisted`() {
        assertTrue(ProvidersTab.shouldPersistSecret(fieldValue = "sk-new", loadedValue = ""))
    }

    @Test
    fun `an edited key is persisted`() {
        assertTrue(ProvidersTab.shouldPersistSecret(fieldValue = "sk-new", loadedValue = "sk-old"))
    }

    @Test
    fun `a deliberately cleared field is persisted so the delete goes through`() {
        assertTrue(ProvidersTab.shouldPersistSecret(fieldValue = "", loadedValue = "sk-old"))
    }

    // ------------------------------------------------------------------
    // resolveSharedSecret tests
    // ------------------------------------------------------------------

    @Test
    fun `resolveSharedSecret returns loaded when neither card edited`() {
        val result = ProvidersTab.resolveSharedSecret(
            activeValue = "sk-loaded",
            otherValue = "sk-loaded",
            loadedValue = "sk-loaded"
        )
        org.junit.Assert.assertEquals("sk-loaded", result)
        // Confirm the no-delete invariant: feeding this result to shouldPersistSecret yields false
        assertFalse(ProvidersTab.shouldPersistSecret(result, "sk-loaded"))
    }

    @Test
    fun `resolveSharedSecret returns loaded when neither card edited and both blank`() {
        val result = ProvidersTab.resolveSharedSecret(
            activeValue = "",
            otherValue = "",
            loadedValue = ""
        )
        org.junit.Assert.assertEquals("", result)
        assertFalse(ProvidersTab.shouldPersistSecret(result, ""))
    }

    @Test
    fun `resolveSharedSecret prefers active card when it differs from loaded`() {
        val result = ProvidersTab.resolveSharedSecret(
            activeValue = "sk-active-edited",
            otherValue = "sk-loaded",
            loadedValue = "sk-loaded"
        )
        org.junit.Assert.assertEquals("sk-active-edited", result)
    }

    @Test
    fun `resolveSharedSecret picks other card when active is untouched but other was edited`() {
        val result = ProvidersTab.resolveSharedSecret(
            activeValue = "sk-loaded",
            otherValue = "sk-other-edited",
            loadedValue = "sk-loaded"
        )
        org.junit.Assert.assertEquals("sk-other-edited", result)
    }

    @Test
    fun `resolveSharedSecret prefers active when both cards edited`() {
        val result = ProvidersTab.resolveSharedSecret(
            activeValue = "sk-active-edited",
            otherValue = "sk-other-edited",
            loadedValue = "sk-loaded"
        )
        org.junit.Assert.assertEquals("sk-active-edited", result)
    }
}
