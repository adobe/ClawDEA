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

import com.adobe.clawdea.cli.CliEnvironment
import com.adobe.clawdea.cli.resolveClaudeCliPath
import com.adobe.clawdea.settings.ClawDEASettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class BedrockAuthProvider(
    private val region: () -> String,
    private val bearerToken: () -> String,
    private val envRegion: () -> String?,
    private val envBearerToken: () -> String?,
) : AuthProvider {
    override val id = "bedrock"

    constructor() : this(
        region = { ClawDEASettings.getInstance().state.bedrockRegion },
        bearerToken = { ClawDEASettings.getInstance().getBedrockBearerToken() },
        envRegion = { System.getenv("AWS_REGION") },
        envBearerToken = { System.getenv("AWS_BEARER_TOKEN_BEDROCK") },
    )

    constructor(region: String, bearerToken: String) : this(
        region = { region },
        bearerToken = { bearerToken },
        envRegion = { null },
        envBearerToken = { null },
    )

    fun resolvedRegion(): String =
        region().ifBlank { envRegion().orEmpty() }

    fun resolvedBearerToken(): String =
        bearerToken().ifBlank { envBearerToken().orEmpty() }

    override fun isConfigured(): Boolean =
        resolvedRegion().isNotBlank() || resolvedBearerToken().isNotBlank()

    override fun applyToEnvironment(env: MutableMap<String, String>) {
        env["CLAUDE_CODE_USE_BEDROCK"] = "1"
        val r = region()
        val t = bearerToken()
        if (r.isNotBlank()) env["AWS_REGION"] = r
        if (t.isNotBlank()) env["AWS_BEARER_TOKEN_BEDROCK"] = t
    }

    override fun validate(): AuthValidation = if (isConfigured()) {
        AuthValidation(valid = true, message = null)
    } else {
        AuthValidation(valid = false, message = "Bedrock not configured. Set region in Settings > Tools > ClawDEA, or export CLAUDE_CODE_USE_BEDROCK=1 in your shell.")
    }

    override fun testConnection(): ConnectionTestResult =
        CliConnectionProbe.probe(this)
}

internal object CliConnectionProbe {
    fun probe(provider: AuthProvider): ConnectionTestResult {
        if (!provider.isConfigured()) {
            return ConnectionTestResult(false, "${provider.id} is not configured.")
        }
        //
        val cliPath = resolveClaudeCliPath(ClawDEASettings.getInstance().state.cliPath)
        val cliFile = java.io.File(cliPath)
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val isWindows = osName.contains("windows")
        val launchable = if (isWindows) cliFile.isFile else cliFile.canExecute()
        if (cliPath == "claude" || !cliFile.isFile || !launchable) {
            return ConnectionTestResult(false, "Claude CLI not found. Cannot test connection.")
        }

        val command = listOf(cliPath, "-p", "--max-turns", "1", "--output-format", "text", "respond with ok")
        val pb = ProcessBuilder(command)
            .redirectErrorStream(true)

        val merged = mutableMapOf<String, String>()
        CliEnvironment.applyTo(merged)
        for ((k, v) in System.getenv()) merged.putIfAbsent(k, v)
        provider.applyToEnvironment(merged)
        val env = pb.environment()
        env.clear()
        env.putAll(merged)

        val start = System.currentTimeMillis()
        return try {
            val proc = pb.start()
            val output = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                .use { it.readText().trim() }
            val finished = proc.waitFor(30, TimeUnit.SECONDS)
            val latency = System.currentTimeMillis() - start

            if (!finished) {
                proc.destroyForcibly()
                return ConnectionTestResult(false, "CLI timed out after 30s.")
            }

            if (proc.exitValue() == 0 && output.isNotBlank()) {
                ConnectionTestResult(true, "Connected via CLI (${latency}ms)", latency)
            } else {
                val detail = output.lines().takeLast(3).joinToString(" ").take(200)
                ConnectionTestResult(false, "CLI error: $detail", latency)
            }
        } catch (e: Exception) {
            ConnectionTestResult(false, "CLI probe failed: ${e.message}")
        }
    }
}
