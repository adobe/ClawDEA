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
package com.adobe.clawdea.provider.openai.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCredentialStoreTest {

    @Test
    fun `get returns empty string when credential not set`() {
        val storage = mutableMapOf<String, String?>()
        val store = ProfileCredentialStore(
            read = { storage[it.serviceName] },
            write = { attr, value -> storage[attr.serviceName] = value },
        )
        assertEquals("", store.get("profile-a"))
    }

    @Test
    fun `set writes credential with scoped key`() {
        val storage = mutableMapOf<String, String?>()
        val store = ProfileCredentialStore(
            read = { storage[it.serviceName] },
            write = { attr, value -> storage[attr.serviceName] = value },
        )
        store.set("profile-b", "secret-token")

        val actualKey = storage.keys.single()
        assertTrue(actualKey.contains("ClawDEA"))
        assertTrue(actualKey.contains("openai-compatible/profile/profile-b/credential"))
        assertEquals("secret-token", storage[actualKey])
    }

    @Test
    fun `get retrieves credential with scoped key`() {
        val actualKey = com.intellij.credentialStore.generateServiceName(
            "ClawDEA",
            "openai-compatible/profile/profile-c/credential",
        )
        val storage = mutableMapOf<String, String?>(actualKey to "retrieved-token")
        val store = ProfileCredentialStore(
            read = { storage[it.serviceName] },
            write = { attr, value -> storage[attr.serviceName] = value },
        )
        assertEquals("retrieved-token", store.get("profile-c"))
    }

    @Test
    fun `clear writes null to remove credential`() {
        val actualKey = com.intellij.credentialStore.generateServiceName(
            "ClawDEA",
            "openai-compatible/profile/profile-d/credential",
        )
        val storage = mutableMapOf<String, String?>(actualKey to "old-token")
        val store = ProfileCredentialStore(
            read = { storage[it.serviceName] },
            write = { attr, value -> storage[attr.serviceName] = value },
        )
        store.clear("profile-d")

        assertEquals(null, storage[actualKey])
    }

    @Test
    fun `set with blank credential writes null`() {
        val storage = mutableMapOf<String, String?>()
        val store = ProfileCredentialStore(
            read = { storage[it.serviceName] },
            write = { attr, value -> storage[attr.serviceName] = value },
        )
        store.set("profile-e", "")

        assertEquals(null, storage["ClawDEA openai-compatible/profile/profile-e/credential"])
    }
}
