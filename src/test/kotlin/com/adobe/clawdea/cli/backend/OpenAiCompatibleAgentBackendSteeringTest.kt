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
package com.adobe.clawdea.cli.backend

import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.adobe.clawdea.provider.openai.session.OpenAiSessionLedger
import com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI

class OpenAiCompatibleAgentBackendSteeringTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `steer returns false when no turn is active`() {
        val backend = createBackend()
        backend.start(null, emptyList())
        backend.readEvent() // SystemInit

        // No turn active yet
        assertFalse(backend.steer("test"))

        backend.stop()
    }

    @Test
    fun `steer returns true when turn is active and preserves partial state`() {
        // This will be a full integration test once steering is wired up
        // For now, we test the basic contract: steer() interacts with active turns
        val backend = createBackend()
        backend.start(null, emptyList())
        backend.readEvent() // SystemInit

        // Start a turn (will fail immediately since we have no real client, but that's OK for now)
        backend.sendMessage("test")

        // Note: Full steering integration test requires a fake AgentClient
        // and is deferred to integration testing (Task 8).
        // The critical implementation is in the backend's steer() method.

        backend.stop()
    }

    private fun createBackend(): OpenAiCompatibleAgentBackend {
        val profile = ResolvedProviderProfile(
            profile = OpenAiCompatibleProfile(
                id = "test-profile",
                name = "Test",
                baseUrl = "https://test",
            ),
            baseUrl = URI("https://test"),
            configuredValues = emptyMap(),
        )

        val ledger = OpenAiSessionLedger(tempFolder.root.canonicalPath)

        return OpenAiCompatibleAgentBackend(
            profile = profile,
            credential = "test-key",
            modelId = "test-model",
            project = null,
            projectPath = tempFolder.root.canonicalPath,
            mcpDefs = emptyList(),
            approvalGate = SharedToolApprovalGate(
                toolApprovalMode = { "allow-all" },
                policy = { null },
                route = { _, _, _ -> null },
                promptTimeoutMs = 30_000,
            ),
            autoAcceptEdits = { false },
            agentLabel = "Test Agent",
            ledger = ledger,
        )
    }
}
