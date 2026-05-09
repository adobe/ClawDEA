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
package com.adobe.clawdea.chat

import com.adobe.clawdea.cli.CliEnvironment
import com.adobe.clawdea.mcp.McpServer
import com.adobe.clawdea.mcp.buildMcpClientConfigJson
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.pty.PtyProcessTtyConnector
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider
import java.awt.Dimension
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities

class InteractiveCommandDialog(
    private val project: Project,
    private val command: String?,
    private val continueSessionId: String? = null,
    private val sessionDir: String? = null,
    private val onResult: ((String) -> Unit)? = null,
) : DialogWrapper(project, true) {

    private val log = Logger.getInstance(InteractiveCommandDialog::class.java)

    private var ptyProcess: PtyProcess? = null
    private val widgetDisposable = Disposer.newDisposable("InteractiveCommandDialog")
    private val resultFired = AtomicBoolean(false)

    init {
        title = if (command.isNullOrBlank()) "Claude Code" else "Claude Code \u2014 $command"
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val settings = JBTerminalSystemSettingsProvider()
        val widget = JBTerminalWidget(project, settings, widgetDisposable)
        widget.component.preferredSize = Dimension(820, 520)

        startTerminalProcess(widget)
        return widget.component
    }

    override fun createActions() = arrayOf(cancelAction)

    private fun snapshotSessionDir(dirPath: String): Map<String, Long> {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return emptyMap()

        return dir.listFiles { f -> f.extension == "jsonl" && !f.isDirectory }
            ?.associate { it.name to it.lastModified() }
            ?: emptyMap()
    }

    private fun startSessionWatcher(process: PtyProcess, dirPath: String, snapshot: Map<String, Long>) {
        val dir = File(dirPath)
        if (!dir.isDirectory) {
            log.warn("Session directory does not exist, skipping watcher: $dirPath")
            return
        }

        Thread {
            try {
                // Let picker render first
                Thread.sleep(1500)
                while (process.isAlive && !resultFired.get()) {
                    Thread.sleep(300)

                    val currentFiles = dir.listFiles { f -> f.extension == "jsonl" && !f.isDirectory }
                    if (currentFiles != null) {
                        for (file in currentFiles) {
                            val previousMtime = snapshot[file.name]
                            val currentMtime = file.lastModified()

                            // New file or modified file
                            if (previousMtime == null || currentMtime > previousMtime) {
                                if (resultFired.compareAndSet(false, true)) {
                                    val sessionId = file.nameWithoutExtension
                                    log.info("Session watcher detected session: $sessionId")

                                    onResult?.invoke(sessionId)

                                    // Kill process and close dialog
                                    try {
                                        if (process.isAlive) {
                                            process.destroy()
                                        }
                                    } catch (e: Exception) {
                                        log.warn("Error destroying process from watcher", e)
                                    }

                                    SwingUtilities.invokeLater {
                                        if (isVisible) close(OK_EXIT_CODE)
                                    }
                                }
                                return@Thread
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                log.warn("Session watcher error", e)
            }
        }.apply {
            isDaemon = true
            name = "ClawDEA-session-watcher"
            start()
        }
    }

    private fun startTerminalProcess(widget: JBTerminalWidget) {
        val pluginSettings = ClawDEASettings.getInstance().state
        val cliPath = resolveCliPath(pluginSettings.cliPath)
        val workDir = project.basePath ?: System.getProperty("user.home")

        val env = LinkedHashMap(System.getenv())
        CliEnvironment.applyTo(env)
        com.adobe.clawdea.auth.AuthManager.getInstance().applyToEnvironment(env)

        try {
            val baseCmd = if (!continueSessionId.isNullOrBlank()) {
                listOf(cliPath, "--resume", continueSessionId)
            } else {
                listOf(cliPath)
            }
            val cmd = (baseCmd + mcpConfigArgs()).toTypedArray()

            val process = PtyProcessBuilder()
                .setCommand(cmd)
                .setDirectory(workDir)
                .setEnvironment(env)
                .start()
            ptyProcess = process

            val connector = PtyProcessTtyConnector(process, StandardCharsets.UTF_8)
            widget.start(connector)

            // Start session watcher if sessionDir is provided
            val snapshot = sessionDir?.let { snapshotSessionDir(it) }
            if (sessionDir != null && snapshot != null) {
                startSessionWatcher(process, sessionDir, snapshot)
            }

            Thread {
                // Wait for CLI to initialize, then send the slash command
                if (!command.isNullOrBlank()) {
                    try {
                        Thread.sleep(800)
                        process.outputStream.write("$command\n".toByteArray(StandardCharsets.UTF_8))
                        process.outputStream.flush()
                    } catch (e: Exception) {
                        log.warn("Failed to send command to interactive terminal", e)
                    }
                }

                // Wait for process to exit, then auto-close dialog
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {}

                // Only close if the watcher hasn't already fired
                if (resultFired.compareAndSet(false, true)) {
                    SwingUtilities.invokeLater {
                        if (isVisible) close(OK_EXIT_CODE)
                    }
                }
            }.apply {
                isDaemon = true
                name = "ClawDEA-interactive-monitor"
                start()
            }
        } catch (e: Exception) {
            log.error("Failed to start interactive CLI process", e)
        }
    }

    /**
     * Returns `--mcp-config <path>` for the current project's McpServer if it's
     * running, otherwise empty. The temp file mirrors what CliProcess writes
     * for the chat-panel session so the interactive `/cc` terminal can also
     * see clawdea-intellij. File is marked deleteOnExit; per-/cc-invocation
     * leak is bounded and never user-visible.
     */
    private fun mcpConfigArgs(): List<String> {
        val port = McpServer.getInstance(project).port
        if (port <= 0) return emptyList()
        val tmpFile = File.createTempFile("clawdea-mcp-cc-", ".json")
        tmpFile.deleteOnExit()
        tmpFile.writeText(buildMcpClientConfigJson(port))
        return listOf("--mcp-config", tmpFile.absolutePath)
    }

    private fun resolveCliPath(configured: String): String {
        if (configured.isNotBlank() && configured != "claude") return configured
        val home = System.getProperty("user.home")
        for (candidate in listOf(
            "$home/.local/bin/claude",
            "$home/.nvm/versions/node/default/bin/claude",
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
        )) {
            if (java.io.File(candidate).canExecute()) return candidate
        }
        return "claude"
    }

    override fun doCancelAction() {
        killProcess()
        super.doCancelAction()
    }

    override fun dispose() {
        killProcess()
        Disposer.dispose(widgetDisposable)
        super.dispose()
    }

    private fun killProcess() {
        val proc = ptyProcess ?: return
        ptyProcess = null
        try {
            if (proc.isAlive) {
                proc.destroy()
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            log.warn("Error stopping interactive CLI process", e)
        }
    }
}
