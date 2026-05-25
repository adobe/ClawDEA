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
package com.adobe.clawdea.mcp.diagnostics

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Synchronous facade over [CompilerManager]'s async compile API for use by MCP tools.
 *
 * The MCP tool handler runs on an HTTP-handler pool thread (not the EDT). It needs a
 * single return value from `get_diagnostics`, but `CompilerManager.compile` is callback-
 * driven and fires its completion notification on the EDT. We bridge the two via a
 * [CountDownLatch] + timeout, scheduling the compile from the EDT and blocking the
 * handler thread until the callback fires.
 *
 * No deadlock risk: the MCP handler thread holds no IDE locks while blocked, and the
 * EDT is free to dispatch the compile, run the build-system tasks asynchronously, and
 * fire the callback when done.
 *
 * Output matches the rest of `McpIdeTools` (`SEVERITY path:line:col message`) so tiers
 * are textually interchangeable.
 */
object CompilerManagerDiagnostics {
    private val log = Logger.getInstance(CompilerManagerDiagnostics::class.java)

    /** Result of [compileSync]. */
    sealed class CompileSyncResult {
        /** Compile finished. Diagnostics text is the formatted message list, or the
         *  'No diagnostics found.' sentinel when both `errors == 0 && warnings == 0`. */
        data class Success(val diagnosticsText: String) : CompileSyncResult()

        /** Compile didn't complete within the timeout. */
        object Timeout : CompileSyncResult()

        /** Compile was aborted (e.g. cancelled by user, or by a competing compile). */
        object Aborted : CompileSyncResult()

        /** CompilerManager rejected the file (not a compilable file type, no module, …). */
        object NotApplicable : CompileSyncResult()

        /** Another compile is already in progress; we declined to start a competing one. */
        object AlreadyInProgress : CompileSyncResult()

        /** CompilerManager.compile threw before scheduling. */
        data class Failed(val cause: Throwable) : CompileSyncResult()
    }

    /**
     * Compile [virtualFile] synchronously via [CompilerManager], returning a structured
     * result. Diagnostics are filtered to messages reported against [virtualFile] (other
     * files compiled incidentally — e.g. dependencies that needed a rebuild — are
     * suppressed from the output).
     *
     * Must NOT be called from the EDT — the method blocks waiting for the EDT-fired
     * callback, which would deadlock the dispatcher.
     */
    fun compileSync(project: Project, virtualFile: VirtualFile, timeoutMillis: Long = 60_000L): CompileSyncResult {
        if (ApplicationManager.getApplication().isDispatchThread) {
            return CompileSyncResult.Failed(IllegalStateException("compileSync must not be called from the EDT"))
        }
        val manager = CompilerManager.getInstance(project)
        if (!manager.isCompilableFileType(virtualFile.fileType)) return CompileSyncResult.NotApplicable
        if (manager.isCompilationActive()) return CompileSyncResult.AlreadyInProgress

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<CompileSyncResult>()

        ApplicationManager.getApplication().invokeLater {
            try {
                manager.compile(arrayOf(virtualFile)) { aborted, _, _, context ->
                    try {
                        resultRef.set(
                            if (aborted) CompileSyncResult.Aborted
                            else CompileSyncResult.Success(formatMessages(context.getMessages(CompilerMessageCategory.ERROR), context.getMessages(CompilerMessageCategory.WARNING), virtualFile)),
                        )
                    } finally {
                        latch.countDown()
                    }
                }
            } catch (t: Throwable) {
                log.warn("CompilerManager.compile threw before scheduling: ${t.javaClass.simpleName}: ${t.message}", t)
                resultRef.set(CompileSyncResult.Failed(t))
                latch.countDown()
            }
        }

        val finished = try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return CompileSyncResult.Failed(e)
        }
        if (!finished) return CompileSyncResult.Timeout
        return resultRef.get() ?: CompileSyncResult.Failed(IllegalStateException("callback fired without setting a result"))
    }

    private fun formatMessages(errors: Array<CompilerMessage>, warnings: Array<CompilerMessage>, target: VirtualFile): String {
        val sb = StringBuilder()
        val targetPath = target.path
        for (m in errors) appendIfMatchesTarget(sb, m, targetPath, "ERROR")
        for (m in warnings) appendIfMatchesTarget(sb, m, targetPath, "WARNING")
        return if (sb.isEmpty()) "No diagnostics found." else sb.toString().trimEnd()
    }

    private fun appendIfMatchesTarget(sb: StringBuilder, m: CompilerMessage, targetPath: String, severity: String) {
        val msgFile = m.virtualFile ?: return
        if (msgFile.path != targetPath) return
        val (line, col) = extractLineColumn(m)
        if (line >= 0 && col >= 0) {
            sb.appendLine("$severity ${msgFile.path}:$line:$col ${m.message.trim()}")
        } else {
            sb.appendLine("$severity ${msgFile.path} ${m.message.trim()}")
        }
    }

    private fun extractLineColumn(m: CompilerMessage): Pair<Int, Int> {
        val nav = m.navigatable
        if (nav is OpenFileDescriptor) {
            val line = nav.line
            val col = nav.column
            // OpenFileDescriptor returns 0-based; surface 1-based to match tier 1/2.
            if (line >= 0) return (line + 1) to (col + 1).coerceAtLeast(1)
        }
        return -1 to -1
    }
}
