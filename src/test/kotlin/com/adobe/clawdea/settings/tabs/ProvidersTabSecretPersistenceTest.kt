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
}
