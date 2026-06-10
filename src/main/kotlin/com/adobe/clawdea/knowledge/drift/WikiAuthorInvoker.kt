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
    /**
     * Invoked with the subprocess's full stream-json stdout after a run so the caller can
     * parse the trailing `result` event and attribute the wiki-author's cost. Default no-op
     * keeps the invoker usable in tests / non-UI contexts.
     */
    private val onStdout: (String) -> Unit = {},
    // Authoring a brand-new concept page (missingConcept) involves reading the
    // index, several concept pages, and multiple find_symbol/find_files
    // verification round-trips through the IDE MCP server before the final
    // Write. 300s was not enough — the process was force-killed mid-run before
    // it ever wrote the page, every cycle. Give heavy authoring room to finish.
    private val timeoutSeconds: Long = 600,
    /** Actual wiki directory; rewrites librarian `.claude/wiki/...` paths in the digest. */
    private val wikiDir: Path? = null,
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
        val digest = WikiAuthorDigestBuilder.build(events, wikiDir)
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
        // Attribute the subprocess's cost (stream-json `result` event in stdout), regardless
        // of exit code — a timed-out/non-zero run can still have produced billable turns.
        if (result.stdout.isNotBlank()) {
            try {
                onStdout(result.stdout)
            } catch (e: Throwable) {
                LOG.warn("wiki-author onStdout hook threw: ${e.message}")
            }
        }
        return when {
            result.timedOut -> {
                LOG.warn("wiki-author subprocess timed out after ${timeoutSeconds}s for ${signatures.size} events; " +
                    "stdout chars=${result.stdout.length} tail: ${result.stdout.takeLast(800)}; " +
                    "stderr chars=${result.stderr.length} tail: ${result.stderr.takeLast(500)}")
                WikiAuthorInvoker.Result(emptySet(), signatures,
                    "wiki-author subprocess timed out after ${timeoutSeconds}s")
            }
            result.exitCode != 0 -> {
                LOG.warn("wiki-author subprocess exit=${result.exitCode} for ${signatures.size} events; " +
                    "stderr tail: ${result.stderr.takeLast(500)}; stdout tail: ${result.stdout.takeLast(500)}")
                WikiAuthorInvoker.Result(emptySet(), signatures,
                    "wiki-author subprocess exit code ${result.exitCode}: ${result.stderr.takeLast(500)}")
            }
            else -> {
                // Strategy-b: exit 0 means "handled". But the author can exit 0
                // while claiming success without actually creating the page (the
                // "it said it did, but it didn't" failure). For a missingConcept
                // the proof is on disk: only ack signatures whose target page now
                // exists. Unverifiable events (no wikiDir, or kinds that edit an
                // existing page) keep the exit-0 contract.
                val (acked, unverified) = events.partition { isAuthored(it) }
                val ackedSignatures = acked.map { it.signature }.toSet()
                val unverifiedSignatures = unverified.map { it.signature }.toSet()
                if (unverifiedSignatures.isNotEmpty()) {
                    LOG.warn("wiki-author exited 0 but ${unverifiedSignatures.size} event(s) left no file on disk; " +
                        "not dismissing so they retry: $unverifiedSignatures")
                }
                LOG.info("wiki-author dismissed ${ackedSignatures.size} events (strategy-b); " +
                    "withheld ${unverifiedSignatures.size} unverified")
                WikiAuthorInvoker.Result(ackedSignatures, unverifiedSignatures, null)
            }
        }
    }

    /**
     * Whether [event] can be treated as authored after an exit-0 run. A
     * `missingConcept` suggestion is only authored if its target page now exists
     * on disk; this is the verification that closes the "claimed but did not
     * write" gap. Other events can't be cheaply verified by existence (they edit
     * an already-present page), so they keep the exit-0 contract.
     */
    private fun isAuthored(event: DriftEvent): Boolean {
        if (event !is DriftEvent.WikiSuggestion) return true
        if (event.kind != SuggestionKind.missingConcept) return true
        val dir = wikiDir ?: return true
        val target = DriftEvent.WikiSuggestion.primaryTarget(event.targetFiles)
        if (target.isBlank()) return true
        val rel = target.removePrefix(".claude/wiki/")
        return java.nio.file.Files.exists(dir.resolve(rel))
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
