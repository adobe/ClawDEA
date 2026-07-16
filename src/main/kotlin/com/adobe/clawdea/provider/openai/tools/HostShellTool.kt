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
package com.adobe.clawdea.provider.openai.tools

import com.adobe.clawdea.cli.CliEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Permission-gated, bounded host shell execution for the HTTP agent backend.
 *
 * ### Flow
 * 1. Routes approval via [SharedToolApprovalGate] (using [missingRouteBehavior]).
 * 2. On approval, runs the command in the project base directory with a timeout
 *    and 1 MiB combined stdout+stderr cap.
 * 3. Returns structured text: exit code, output, timeout/truncation flags.
 *
 * ### Threading
 * [execute] blocks during approval and process execution — must be called off-EDT.
 */
class HostShellTool(
    private val project: Project?,
    private val approvalGate: SharedToolApprovalGate,
    private val processRunner: ProcessRunner = RealProcessRunner,
    private val missingRouteBehavior: MissingRouteBehavior,
) {
    private val log = Logger.getInstance(HostShellTool::class.java)

    /**
     * Execute a shell command with approval + bounds. Returns structured result.
     * Blocks on approval + process execution — call off-EDT.
     */
    fun execute(command: String, toolUseId: String): ToolExecutionResult {
        val inputJson = com.google.gson.JsonObject().apply {
            addProperty("command", command)
        }.toString()

        val approved = approvalGate.approve(
            toolName = "Bash",
            inputJson = inputJson,
            toolUseId = toolUseId,
            autoAcceptEdit = false,
            missingRouteBehavior = missingRouteBehavior,
        )

        if (!approved) {
            return ToolExecutionResult(
                toolCallId = toolUseId,
                content = "Command not approved",
                isError = true,
            )
        }

        val workingDir = project?.basePath ?: System.getProperty("user.dir")
        val result = processRunner.run(
            command = command,
            workingDir = workingDir,
            env = buildEnv(),
            timeoutMs = DEFAULT_TIMEOUT_MS,
        )

        val content = buildString {
            append("exit code: ${result.exitCode}\n")
            if (result.output.isNotEmpty()) {
                append(result.output)
            }
            if (result.timedOut) {
                append("\n[command timed out after ${DEFAULT_TIMEOUT_MS / 1000}s]")
            }
            if (result.truncated) {
                append("\n[output truncated at 1 MiB]")
            }
        }

        return ToolExecutionResult(
            toolCallId = toolUseId,
            content = content,
            isError = false,
        )
    }

    private fun buildEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        if (project != null) {
            CliEnvironment.applyTo(env)
        }
        for ((k, v) in System.getenv()) {
            env.putIfAbsent(k, v)
        }
        return env
    }

    /**
     * Injectable process runner for testing.
     */
    interface ProcessRunner {
        fun run(command: String, workingDir: String, env: Map<String, String>, timeoutMs: Long): ProcessResult
    }

    data class ProcessResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
        val truncated: Boolean,
    )

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 120_000L // 2 minutes
        private const val MAX_OUTPUT_BYTES = 1_048_576 // 1 MiB

        /**
         * Read up to [maxBytes] from [reader], returning (content, truncated).
         * Stops reading once the cap is reached.
         */
        internal fun readBounded(reader: java.io.Reader, maxBytes: Int): Pair<String, Boolean> {
            val buf = StringBuilder()
            var truncated = false
            try {
                while (true) {
                    val ch = reader.read()
                    if (ch == -1) break
                    if (buf.length < maxBytes) {
                        buf.append(ch.toChar())
                    } else {
                        truncated = true
                        break
                    }
                }
            } catch (e: Exception) {
                // Treat read errors as end-of-stream
            }
            return Pair(buf.toString(), truncated)
        }
    }

    /**
     * Real process runner that spawns a shell subprocess.
     */
    private object RealProcessRunner : ProcessRunner {
        private val log = Logger.getInstance("HostShellTool.RealProcessRunner")

        override fun run(
            command: String,
            workingDir: String,
            env: Map<String, String>,
            timeoutMs: Long,
        ): ProcessResult {
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            val isWindows = osName.contains("windows")

            val shellCommand = if (isWindows) {
                listOf("cmd.exe", "/c", command)
            } else {
                val shell = env["SHELL"] ?: "/bin/sh"
                listOf(shell, "-lc", command)
            }

            val pb = ProcessBuilder(shellCommand)
                .directory(File(workingDir))
                .redirectErrorStream(true)

            pb.environment().clear()
            pb.environment().putAll(env)

            val startTime = System.currentTimeMillis()
            val proc = pb.start()

            val reader = proc.inputStream.bufferedReader()
            val (output, truncated) = HostShellTool.readBounded(reader, MAX_OUTPUT_BYTES)

            // If output was capped, kill the process immediately to avoid draining a fast infinite producer
            val completed = if (truncated) {
                proc.destroyForcibly()
                proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            }
            val timedOut = !completed

            if (timedOut) {
                proc.destroyForcibly()
            }

            // Exit code: if truncated and process killed, return its actual exit or -1; if timed out, -1
            val exitCode = if (completed) proc.exitValue() else -1

            return ProcessResult(
                exitCode = exitCode,
                output = output,
                timedOut = timedOut,
                truncated = truncated,
            )
        }
    }
}
