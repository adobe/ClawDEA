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
package com.adobe.clawdea.cli

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Outcome of a one-shot subprocess run. On timeout, [stdout]/[stderr] hold whatever drained first. */
data class SubprocessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/**
 * One-shot subprocess runner that drains stdout and stderr on **separate threads before**
 * `waitFor`, so a child which fills the ~64 KB OS pipe buffer cannot deadlock the wait. This is the
 * single correct shape for the run/drain/timeout pattern that was hand-copied — often with the
 * read-after-`waitFor` deadlock — across auth probes, wiki subprocesses, the gateway, and
 * `get_diagnostics` (Tier 4.2 / 0.9 of the refactoring backlog).
 *
 * Environment is intentionally **not** owned here: call sites differ in what they need (login-shell
 * merge, provider-var stripping for subscription probes, per-selection auth), so [configureEnvironment]
 * is applied to the live `ProcessBuilder` environment map and each caller keeps its exact semantics.
 *
 * On timeout the process is force-killed; the return has [SubprocessResult.timedOut] = true and
 * [SubprocessResult.exitCode] = -1.
 */
object AgentSubprocess {

    private const val DRAIN_JOIN_MILLIS = 500L

    fun run(
        command: List<String>,
        timeoutMillis: Long,
        workingDir: File? = null,
        redirectErrorStream: Boolean = false,
        redirectInput: ProcessBuilder.Redirect? = null,
        configureEnvironment: (MutableMap<String, String>) -> Unit = {},
    ): SubprocessResult {
        val pb = ProcessBuilder(command).redirectErrorStream(redirectErrorStream)
        if (workingDir != null) pb.directory(workingDir)
        if (redirectInput != null) pb.redirectInput(redirectInput)
        pb.environment().apply(configureEnvironment)

        val proc = pb.start()
        val out = StringBuilder()
        val err = StringBuilder()
        val outDrain = drain(proc.inputStream, out)
        val errDrain = if (redirectErrorStream) null else drain(proc.errorStream, err)

        val exited = proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!exited) {
            proc.destroyForcibly()
            proc.waitFor(DRAIN_JOIN_MILLIS, TimeUnit.MILLISECONDS)
            outDrain.join(DRAIN_JOIN_MILLIS)
            errDrain?.join(DRAIN_JOIN_MILLIS)
            return SubprocessResult(exitCode = -1, stdout = out.toString(), stderr = err.toString(), timedOut = true)
        }
        outDrain.join(DRAIN_JOIN_MILLIS)
        errDrain?.join(DRAIN_JOIN_MILLIS)
        return SubprocessResult(
            exitCode = proc.exitValue(),
            stdout = out.toString(),
            stderr = err.toString(),
            timedOut = false,
        )
    }

    private fun drain(stream: InputStream, sink: StringBuilder): Thread =
        Thread {
            try {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        sink.append(buffer, 0, n)
                    }
                }
            } catch (_: Exception) {
                // Stream closed by force-kill / EOF race — return whatever was captured.
            }
        }.apply {
            isDaemon = true
            name = "ClawDEA-subprocess-drain"
            start()
        }
}
