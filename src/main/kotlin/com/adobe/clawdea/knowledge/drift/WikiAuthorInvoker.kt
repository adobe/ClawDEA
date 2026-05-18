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

import com.adobe.clawdea.knowledge.wiki.WikiAgentsArg
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Invokes the wiki-author subagent on a digest of drift events. Strategy (b)
 * (see design §5.2): exit 0 dismisses every event in the digest, non-zero exit
 * dismisses nothing.
 */
interface WikiAuthorInvoker {
    data class Result(
        val actedOnSignatures: Set<String>,
        val skippedSignatures: Set<String>,
        val errorMessage: String?,
    )
    suspend fun invoke(events: List<DriftEvent>): Result
}

/**
 * Default implementation: spawns `claude -p` with `--agents <author-only-json>`
 * on Dispatchers.IO. Uses [WikiAuthorDigestBuilder] for the prompt.
 */
class DefaultWikiAuthorInvoker(
    private val runner: ProcessRunner = DefaultProcessRunner,
    private val claudeCliPath: String,
    private val projectRoot: Path,
    private val mcpPort: Int = 0,
    private val modelId: String = "",
    private val timeoutSeconds: Long = 300,
) : WikiAuthorInvoker {

    override suspend fun invoke(events: List<DriftEvent>): WikiAuthorInvoker.Result {
        if (events.isEmpty()) {
            return WikiAuthorInvoker.Result(emptySet(), emptySet(), null)
        }
        val signatures = events.map { it.signature }.toSet()
        LOG.info("wiki-author invoke: ${events.size} events; kinds=${events.groupingBy { it::class.simpleName }.eachCount()}")
        val agentsJson = try {
            WikiAgentsArg.buildAuthorOnlyJson()
        } catch (e: Throwable) {
            LOG.warn("wiki-author failed to build --agents arg: ${e.message}", e)
            return WikiAuthorInvoker.Result(emptySet(), signatures,
                "Failed to build wiki-author --agents arg: ${e.message}")
        }
        val digest = WikiAuthorDigestBuilder.build(events)
        val mcpConfigFile = if (mcpPort > 0) {
            try {
                val tmp = java.io.File.createTempFile("clawdea-mcp-wiki-author-", ".json")
                tmp.deleteOnExit()
                tmp.writeText(com.adobe.clawdea.mcp.buildMcpClientConfigJson(mcpPort))
                tmp
            } catch (e: Throwable) {
                LOG.warn("wiki-author failed to write MCP config: ${e.message}", e)
                null
            }
        } else null

        val command = mutableListOf(
            claudeCliPath,
            "-p",
            "--output-format", "stream-json",
            "--verbose",
            "--no-session-persistence",
            "--permission-mode", "bypassPermissions",
            "--agents", agentsJson,
            "--disallowedTools", "Bash,mcp__clawdea-intellij__propose_write,mcp__clawdea-intellij__propose_edit,mcp__clawdea-intellij__propose_multi_edit",
        )
        if (modelId.isNotBlank()) {
            command.addAll(listOf("--model", modelId))
            LOG.info("wiki-author using model: $modelId")
        } else {
            LOG.info("wiki-author no model selected; CC will use its default")
        }
        if (mcpConfigFile != null) {
            command.addAll(listOf("--mcp-config", mcpConfigFile.absolutePath))
        }
        command.addAll(listOf("--", digest))

        val result = withContext(Dispatchers.IO) {
            try {
                runner.run(command, projectRoot, timeoutSeconds)
            } catch (e: Exception) {
                LOG.warn("wiki-author runner.run threw: ${e.javaClass.simpleName}: ${e.message}", e)
                ProcessResult(-1, "", "${e.javaClass.simpleName}: ${e.message}", timedOut = false)
            }
        }
        return when {
            result.timedOut -> {
                LOG.warn("wiki-author subprocess timed out after ${timeoutSeconds}s for ${signatures.size} events")
                WikiAuthorInvoker.Result(emptySet(), signatures,
                    "wiki-author subprocess timed out after ${timeoutSeconds}s")
            }
            result.exitCode != 0 -> {
                LOG.warn("wiki-author subprocess exit=${result.exitCode} for ${signatures.size} events; stderr tail: ${result.stderr.takeLast(500)}")
                WikiAuthorInvoker.Result(emptySet(), signatures,
                    "wiki-author subprocess exit code ${result.exitCode}: ${result.stderr.takeLast(500)}")
            }
            else -> {
                LOG.info("wiki-author dismissed ${signatures.size} events (strategy-b)")
                WikiAuthorInvoker.Result(signatures, emptySet(), null)
            }
        }
    }

    interface ProcessRunner {
        fun run(command: List<String>, projectRoot: Path, timeoutSeconds: Long): ProcessResult
    }

    data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    object DefaultProcessRunner : ProcessRunner {
        override fun run(command: List<String>, projectRoot: Path, timeoutSeconds: Long): ProcessResult {
            val pb = ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(false)
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            val merged = mutableMapOf<String, String>()
            com.adobe.clawdea.cli.CliEnvironment.applyTo(merged)
            for ((k, v) in System.getenv()) merged.putIfAbsent(k, v)
            com.adobe.clawdea.auth.AuthManager.getInstance().applyToEnvironment(merged)
            val env = pb.environment()
            env.clear()
            env.putAll(merged)
            val process = pb.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val out = drain(process.inputStream.bufferedReader(StandardCharsets.UTF_8), stdout)
            val err = drain(process.errorStream.bufferedReader(StandardCharsets.UTF_8), stderr)
            return if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                out.join(500); err.join(500)
                ProcessResult(-1, stdout.toString(), stderr.toString(), timedOut = true)
            } else {
                out.join(500); err.join(500)
                ProcessResult(process.exitValue(), stdout.toString(), stderr.toString(), timedOut = false)
            }
        }

        private fun drain(reader: BufferedReader, output: StringBuilder): Thread =
            Thread { reader.useLines { lines -> for (line in lines) output.appendLine(line) } }
                .apply { isDaemon = true; start() }
    }

    companion object {
        private val LOG = Logger.getInstance(DefaultWikiAuthorInvoker::class.java)
    }
}
