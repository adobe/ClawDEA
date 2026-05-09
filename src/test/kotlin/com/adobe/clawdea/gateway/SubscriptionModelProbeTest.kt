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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SubscriptionModelProbeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `readAccessToken returns token from well-formed credentials file`() {
        val file = writeCredentials("""{"claudeAiOauth":{"accessToken":"sk-oat-abc","refreshToken":"x"}}""")
        assertEquals("sk-oat-abc", SubscriptionModelProbe.readAccessToken(file))
    }

    @Test
    fun `readAccessToken returns null when file is missing`() {
        assertNull(SubscriptionModelProbe.readAccessToken(File(tempFolder.root, "does-not-exist.json")))
    }

    @Test
    fun `readAccessToken returns null when file is empty`() {
        assertNull(SubscriptionModelProbe.readAccessToken(writeCredentials("")))
    }

    @Test
    fun `readAccessToken returns null when json is malformed`() {
        assertNull(SubscriptionModelProbe.readAccessToken(writeCredentials("{not json")))
    }

    @Test
    fun `readAccessToken returns null when claudeAiOauth block is missing`() {
        assertNull(SubscriptionModelProbe.readAccessToken(writeCredentials("""{"other":{}}""")))
    }

    @Test
    fun `readAccessToken returns null when accessToken is blank`() {
        assertNull(SubscriptionModelProbe.readAccessToken(writeCredentials("""{"claudeAiOauth":{"accessToken":""}}""")))
    }

    @Test
    fun `parseAccessTokenFromJson handles keychain-shaped payload`() {
        val payload = """{"claudeAiOauth":{"accessToken":"sk-ant-oat-xyz","scopes":["user:inference"]}}"""
        assertEquals("sk-ant-oat-xyz", SubscriptionModelProbe.parseAccessTokenFromJson(payload))
    }

    @Test
    fun `parseAccessTokenFromJson returns null for blank input`() {
        assertNull(SubscriptionModelProbe.parseAccessTokenFromJson(""))
        assertNull(SubscriptionModelProbe.parseAccessTokenFromJson("   \n  "))
    }

    @Test
    fun `probe returns null when tokenSource yields nothing`() {
        val probe = SubscriptionModelProbe(
            credentialsFile = File(tempFolder.root, "missing.json"),
            tokenSource = { null },
        )
        assertNull(probe.probe())
    }

    private fun writeCredentials(content: String): File {
        val file = tempFolder.newFile("credentials.json")
        file.writeText(content)
        return file
    }
}
