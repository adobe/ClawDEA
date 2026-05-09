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
package com.adobe.clawdea.knowledge.drift

import com.adobe.clawdea.auth.AuthManager
import com.adobe.clawdea.cli.CliEnvironment
import com.adobe.clawdea.cli.resolveClaudeCliPath
import com.adobe.clawdea.settings.ClawDEASettings
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

sealed class DreamInvocationResult {
    data class Available(val json: String) : DreamInvocationResult()
    data class Unavailable(val reason: String) : DreamInvocationResult()
}

interface DreamInvocation {
    fun run(projectRoot: Path, prompt: String): DreamInvocationResult
}

internal data class DreamProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

internal interface DreamProcessRunner {
    fun run(
        command: List<String>,
        projectRoot: Path,
        environment: Map<String, String>,
        timeoutSeconds: Long,
    ): DreamProcessResult
}

class ClaudeDreamInvocation internal constructor(
    private val timeoutSeconds: Long = 120,
    private val runner: DreamProcessRunner = DefaultDreamProcessRunner(),
    private val cliPathProvider: () -> String = {
        resolveClaudeCliPath(ClawDEASettings.getInstance().state.cliPath)
    },
    private val environmentProvider: () -> MutableMap<String, String> = ::buildClaudeSubprocessEnvironment,
) : DreamInvocation {

    private var capability: Boolean? = null

    override fun run(projectRoot: Path, prompt: String): DreamInvocationResult {
        val cliPath = cliPathProvider()
        val environment = environmentProvider()
        if (!hasDreamCapability(cliPath, projectRoot, environment)) {
            return DreamInvocationResult.Unavailable("Dreams unavailable")
        }

        val command = listOf(
            cliPath,
            "-p",
            "--output-format",
            "text",
            "--no-session-persistence",
            "--disallowedTools",
            DISALLOWED_TOOLS,
            prompt,
        )

        val result = try {
            runner.run(command, projectRoot, environment, timeoutSeconds)
        } catch (e: Exception) {
            return DreamInvocationResult.Unavailable(e.message?.takeIf { it.isNotBlank() } ?: "Dreams failed")
        }

        if (result.timedOut) return DreamInvocationResult.Unavailable("Dreams timed out")
        if (result.exitCode != 0) return DreamInvocationResult.Unavailable(nonZeroReason(result))
        return DreamInvocationResult.Available(result.stdout.trim())
    }

    private fun hasDreamCapability(cliPath: String, projectRoot: Path, environment: Map<String, String>): Boolean {
        capability?.let { return it }
        val result = try {
            runner.run(listOf(cliPath, "--help"), projectRoot, environment, timeoutSeconds)
        } catch (_: Exception) {
            capability = false
            return false
        }
        val output = "${result.stdout}\n${result.stderr}"
        val supported = !result.timedOut &&
            result.exitCode == 0 &&
            DREAM_CAPABILITY_REGEX.containsMatchIn(output) &&
            output.contains("--disallowedTools")
        capability = supported
        return supported
    }

    private fun nonZeroReason(result: DreamProcessResult): String =
        result.stderr
            .lines()
            .filter { it.isNotBlank() }
            .takeLast(5)
            .joinToString("\n")
            .ifBlank { "Dreams failed with exit code ${result.exitCode}" }

    private companion object {
        const val DISALLOWED_TOOLS = "Write,Edit,MultiEdit,NotebookEdit,Bash"
        val DREAM_CAPABILITY_REGEX = Regex("""(?i)(/dream|\bdream\b)""")
    }
}

private class DefaultDreamProcessRunner : DreamProcessRunner {

    override fun run(
        command: List<String>,
        projectRoot: Path,
        environment: Map<String, String>,
        timeoutSeconds: Long,
    ): DreamProcessResult {
        val process = ProcessBuilder(command)
            .directory(projectRoot.toFile())
            .redirectErrorStream(false)
            .apply {
                this.environment().clear()
                this.environment().putAll(environment)
            }
            .start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = drain(process.inputStream.bufferedReader(StandardCharsets.UTF_8), stdout)
        val stderrThread = drain(process.errorStream.bufferedReader(StandardCharsets.UTF_8), stderr)

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            stdoutThread.join(500)
            stderrThread.join(500)
            return DreamProcessResult(exitCode = -1, stdout = stdout.toString(), stderr = stderr.toString(), timedOut = true)
        }

        stdoutThread.join(500)
        stderrThread.join(500)
        return DreamProcessResult(
            exitCode = process.exitValue(),
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = false,
        )
    }

    private fun drain(reader: BufferedReader, output: StringBuilder): Thread =
        Thread {
            reader.useLines { lines ->
                for (line in lines) output.appendLine(line)
            }
        }.apply {
            isDaemon = true
            start()
        }
}

private fun buildClaudeSubprocessEnvironment(): MutableMap<String, String> {
    val merged = mutableMapOf<String, String>()
    CliEnvironment.applyTo(merged)
    for ((key, value) in System.getenv()) {
        merged.putIfAbsent(key, value)
    }
    AuthManager.getInstance().applyToEnvironment(merged)
    return merged
}
