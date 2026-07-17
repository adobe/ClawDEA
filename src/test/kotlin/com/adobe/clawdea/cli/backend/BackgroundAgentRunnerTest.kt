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

import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.openai.agent.AgentCompletionRequest
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent
import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import com.adobe.clawdea.provider.openai.profile.ProfileStore
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore
import com.adobe.clawdea.settings.ClawDEASettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundAgentRunnerTest {

    @Test
    fun `runText with openai-compatible returns collected text on success`() {
        val fakeOpenAiClient: (ResolvedProviderProfile, String, AgentCompletionRequest) -> Flow<AgentStreamEvent> =
            { _, _, _ ->
                flowOf(
                    AgentStreamEvent.Text("hello"),
                    AgentStreamEvent.Text(" world"),
                    AgentStreamEvent.Finished("stop")
                )
            }

        val fakeProfile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test",
            baseUrl = "http://localhost",
            streaming = true,
        )

        val fakeProfileStore: (String, Map<String, String>) -> ResolvedProviderProfile? = { _, _ ->
            ResolvedProviderProfile(
                profile = fakeProfile,
                baseUrl = URI("http://localhost"),
                configuredValues = emptyMap()
            )
        }

        val fakeCredentialStore: (String) -> String = { "fake-credential" }

        val runner = BackgroundAgentRunner(
            openAiClient = fakeOpenAiClient,
            profileResolver = fakeProfileStore,
            credentialGetter = fakeCredentialStore,
            cliRunner = { _, _, _ -> Result.failure(RuntimeException("Should not be called")) }
        )

        val result = runner.runText(
            selection = AgentSelection("openai-compatible", "test-profile", "gpt-4"),
            prompt = "Hello"
        )

        assertTrue(result.isSuccess)
        assertEquals("hello world", result.getOrNull())
    }

    @Test
    fun `runText with openai-compatible returns failure on HTTP error`() {
        val fakeOpenAiClient: (ResolvedProviderProfile, String, AgentCompletionRequest) -> Flow<AgentStreamEvent> =
            { _, _, _ ->
                flowOf(AgentStreamEvent.Failure(500, "Internal Server Error", null))
            }

        val fakeProfile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test",
            baseUrl = "http://localhost",
            streaming = true,
        )

        val fakeProfileStore: (String, Map<String, String>) -> ResolvedProviderProfile? = { _, _ ->
            ResolvedProviderProfile(
                profile = fakeProfile,
                baseUrl = URI("http://localhost"),
                configuredValues = emptyMap()
            )
        }

        val fakeCredentialStore: (String) -> String = { "fake-credential" }

        val runner = BackgroundAgentRunner(
            openAiClient = fakeOpenAiClient,
            profileResolver = fakeProfileStore,
            credentialGetter = fakeCredentialStore,
            cliRunner = { _, _, _ -> Result.failure(RuntimeException("Should not be called")) }
        )

        val result = runner.runText(
            selection = AgentSelection("openai-compatible", "test-profile", "gpt-4"),
            prompt = "Hello"
        )

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("Internal Server Error") == true)
    }

    @Test
    fun `runText with openai-compatible returns failure when profile missing`() {
        val fakeOpenAiClient: (ResolvedProviderProfile, String, AgentCompletionRequest) -> Flow<AgentStreamEvent> =
            { _, _, _ -> flowOf() }

        val fakeProfileStore: (String, Map<String, String>) -> ResolvedProviderProfile? = { _, _ -> null }

        val fakeCredentialStore: (String) -> String = { "fake-credential" }

        val runner = BackgroundAgentRunner(
            openAiClient = fakeOpenAiClient,
            profileResolver = fakeProfileStore,
            credentialGetter = fakeCredentialStore,
            cliRunner = { _, _, _ -> Result.failure(RuntimeException("Should not be called")) }
        )

        val result = runner.runText(
            selection = AgentSelection("openai-compatible", "test-profile", "gpt-4"),
            prompt = "Hello"
        )

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("profile") == true)
    }

    @Test
    fun `runText with openai-compatible returns failure when credential missing`() {
        val fakeOpenAiClient: (ResolvedProviderProfile, String, AgentCompletionRequest) -> Flow<AgentStreamEvent> =
            { _, _, _ -> flowOf() }

        val fakeProfile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test",
            baseUrl = "http://localhost",
            streaming = true,
        )

        val fakeProfileStore: (String, Map<String, String>) -> ResolvedProviderProfile? = { _, _ ->
            ResolvedProviderProfile(
                profile = fakeProfile,
                baseUrl = URI("http://localhost"),
                configuredValues = emptyMap()
            )
        }

        val fakeCredentialStore: (String) -> String = { "" }

        val runner = BackgroundAgentRunner(
            openAiClient = fakeOpenAiClient,
            profileResolver = fakeProfileStore,
            credentialGetter = fakeCredentialStore,
            cliRunner = { _, _, _ -> Result.failure(RuntimeException("Should not be called")) }
        )

        val result = runner.runText(
            selection = AgentSelection("openai-compatible", "test-profile", "gpt-4"),
            prompt = "Hello"
        )

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("credential") == true)
    }

    @Test
    fun `runText with claude-cli dispatches to CLI runner`() {
        val fakeOpenAiClient: (ResolvedProviderProfile, String, AgentCompletionRequest) -> Flow<AgentStreamEvent> =
            { _, _, _ -> flowOf() }

        var cliCalled = false
        val cliRunner: (AgentSelection, String, String?) -> Result<String> = { sel, prompt, systemPrompt ->
            cliCalled = true
            assertEquals("anthropic", sel.providerId)
            assertEquals("test prompt", prompt)
            assertEquals(null, systemPrompt)
            Result.success("cli response")
        }

        val runner = BackgroundAgentRunner(
            openAiClient = fakeOpenAiClient,
            profileResolver = { _, _ -> null },
            credentialGetter = { "" },
            cliRunner = cliRunner
        )

        val result = runner.runText(
            selection = AgentSelection("anthropic", null, "claude-opus-4"),
            prompt = "test prompt"
        )

        assertTrue(cliCalled)
        assertTrue(result.isSuccess)
        assertEquals("cli response", result.getOrNull())
    }

    @Test
    fun `runText with claude-cli passes system prompt when provided`() {
        val fakeOpenAiClient: (ResolvedProviderProfile, String, AgentCompletionRequest) -> Flow<AgentStreamEvent> =
            { _, _, _ -> flowOf() }

        var receivedSystemPrompt: String? = null
        val cliRunner: (AgentSelection, String, String?) -> Result<String> = { _, _, systemPrompt ->
            receivedSystemPrompt = systemPrompt
            Result.success("cli response")
        }

        val runner = BackgroundAgentRunner(
            openAiClient = fakeOpenAiClient,
            profileResolver = { _, _ -> null },
            credentialGetter = { "" },
            cliRunner = cliRunner
        )

        runner.runText(
            selection = AgentSelection("anthropic", null, "claude-opus-4"),
            prompt = "test prompt",
            systemPrompt = "You are a helpful assistant"
        )

        assertEquals("You are a helpful assistant", receivedSystemPrompt)
    }
}
