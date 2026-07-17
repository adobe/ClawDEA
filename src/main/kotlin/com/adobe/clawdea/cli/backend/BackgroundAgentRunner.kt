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

import com.adobe.clawdea.auth.AuthManager
import com.adobe.clawdea.cli.CliEnvironment
import com.adobe.clawdea.cli.CliEventParser
import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.provider.openai.agent.AgentCompletionRequest
import com.adobe.clawdea.provider.openai.agent.AgentMessage
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent
import com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore
import com.adobe.clawdea.provider.openai.client.OpenAiCompatibleClient
import com.adobe.clawdea.provider.openai.profile.ProfileStore
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.adobe.clawdea.settings.ClawDEASettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Provider-agnostic text-only one-shot agent runner for background tasks (completions, wiki generation).
 *
 * Dispatches by [BackendKind]:
 * - OPENAI_COMPATIBLE_HTTP: uses [OpenAiCompatibleClient] with profile/credential resolution
 * - CLAUDE_CLI: shells out to `claude -p` one-shot with auth applied via environment
 * - CODEX_APP_SERVER: returns failure (codex one-shot not implemented; rare combination for background tasks)
 *
 * **Threading contract**: runs on the caller's thread. Callers MUST invoke off-EDT. Does NOT read
 * PasswordSafe on the EDT (credential resolution happens in the injected functions, which are called
 * on the caller's thread).
 */
class BackgroundAgentRunner(
    private val openAiClient: (ResolvedProviderProfile, String, AgentCompletionRequest) -> Flow<AgentStreamEvent> =
        { profile, credential, request ->
            OpenAiCompatibleClient().streamAgentCompletion(profile, credential, request)
        },
    private val profileResolver: (String, Map<String, String>) -> ResolvedProviderProfile? =
        { profileId, env ->
            ProfileStore(ClawDEASettings.getInstance()).resolve(profileId, env)
        },
    private val credentialGetter: (String) -> String =
        { profileId ->
            ProfileCredentialStore().get(profileId)
        },
    private val cliRunner: (AgentSelection, String, String?) -> Result<String> =
        { selection, prompt, systemPrompt ->
            runClaudeCliOneShot(selection, prompt, systemPrompt)
        },
) {

    /**
     * Runs a text-only one-shot agent request and returns the collected text.
     *
     * @param selection the provider/profile/model to use
     * @param prompt the user prompt
     * @param systemPrompt optional system prompt
     * @return [Result.success] with the collected text, or [Result.failure] on error
     */
    fun runText(
        selection: AgentSelection,
        prompt: String,
        systemPrompt: String? = null,
    ): Result<String> {
        val backendKind = ProviderRegistry.require(selection.providerId).backendKind

        return when (backendKind) {
            BackendKind.OPENAI_COMPATIBLE_HTTP -> runOpenAiCompatible(selection, prompt, systemPrompt)
            BackendKind.CLAUDE_CLI -> cliRunner(selection, prompt, systemPrompt)
            BackendKind.CODEX_APP_SERVER -> Result.failure(
                RuntimeException("Codex background one-shot not supported")
            )
        }
    }

    private fun runOpenAiCompatible(
        selection: AgentSelection,
        prompt: String,
        systemPrompt: String?,
    ): Result<String> = runBlocking {
        val env = System.getenv()
        val profile = profileResolver(selection.profileId ?: "", env)
            ?: return@runBlocking Result.failure(RuntimeException("OpenAI-compatible profile not found"))

        val credential = credentialGetter(selection.profileId ?: "")
        if (credential.isBlank()) {
            return@runBlocking Result.failure(RuntimeException("OpenAI-compatible credential not found"))
        }

        val messages = mutableListOf<AgentMessage>()
        if (systemPrompt != null) {
            messages.add(AgentMessage(role = "system", content = systemPrompt))
        }
        messages.add(AgentMessage(role = "user", content = prompt))

        val request = AgentCompletionRequest(
            model = selection.modelId,
            messages = messages,
            tools = emptyList(),
            maxTokens = 4096,
            stream = profile.profile.streaming,
        )

        val collected = StringBuilder()
        var failure: AgentStreamEvent.Failure? = null

        try {
            openAiClient(profile, credential, request).collect { event ->
                when (event) {
                    is AgentStreamEvent.Text -> collected.append(event.text)
                    is AgentStreamEvent.Failure -> {
                        failure = event
                    }
                    is AgentStreamEvent.Finished -> {
                        // Done
                    }
                    else -> {
                        // Ignore reasoning, tool fragments, usage
                    }
                }
            }
        } catch (e: Exception) {
            return@runBlocking Result.failure(e)
        }

        if (failure != null) {
            return@runBlocking Result.failure(RuntimeException(failure!!.message))
        }

        Result.success(collected.toString())
    }

    companion object {
        /**
         * Runs a one-shot `claude -p` command with the given selection and prompt.
         * Mirrors the ClaudeGateway's CLI path but simplified for text-only collection.
         */
        private fun runClaudeCliOneShot(
            selection: AgentSelection,
            prompt: String,
            systemPrompt: String?,
        ): Result<String> {
            val cliPath = resolveCliPath()
                ?: return Result.failure(RuntimeException("Claude CLI not found"))

            val command = mutableListOf(
                cliPath,
                "-p",
                "--output-format", "stream-json",
                "--no-session-persistence",
            )

            if (selection.modelId.isNotBlank()) {
                command.addAll(listOf("--model", selection.modelId))
            }

            if (systemPrompt != null) {
                command.add("--system-prompt")
                command.add(systemPrompt)
            }

            command.add(prompt)

            return try {
                val pb = ProcessBuilder(command)
                    .redirectErrorStream(false)

                val merged = mutableMapOf<String, String>()
                CliEnvironment.applyTo(merged)
                for ((k, v) in System.getenv()) merged.putIfAbsent(k, v)
                AuthManager.getInstance().applyToEnvironment(merged, selection)
                val env = pb.environment()
                env.clear()
                env.putAll(merged)

                val proc = pb.start()
                val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                val cliParser = CliEventParser()
                val collected = StringBuilder()

                reader.useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        val event = cliParser.parse(line)
                        when (event) {
                            is CliEvent.TextDelta -> collected.append(event.text)
                            is CliEvent.AssistantMessage ->
                                if (event.text.isNotBlank()) collected.append(event.text)
                            is CliEvent.Result ->
                                if (event.isError) collected.append(event.text)
                            else -> {}
                        }
                    }
                }

                val finished = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return Result.failure(RuntimeException("Claude CLI timed out"))
                }

                val exitCode = proc.exitValue()
                if (exitCode != 0) {
                    return Result.failure(RuntimeException("Claude CLI exited with code $exitCode"))
                }

                Result.success(collected.toString())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        private fun resolveCliPath(): String? {
            val paths = listOf("/usr/local/bin/claude", "/opt/homebrew/bin/claude", "claude")
            for (path in paths) {
                try {
                    val proc = ProcessBuilder(path, "--version").start()
                    proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    if (proc.exitValue() == 0) return path
                } catch (_: Exception) {
                    // Try next
                }
            }
            return System.getenv("CLAUDE_CLI_PATH")
        }
    }
}
