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
package com.adobe.clawdea.mcp

import com.adobe.clawdea.chat.permission.AutoAllowSignal
import com.adobe.clawdea.chat.permission.ClaudePermissionSettingsReader
import com.adobe.clawdea.chat.permission.PermissionPolicy
import com.adobe.clawdea.chat.permission.PermissionRouterRegistry
import com.adobe.clawdea.debug.McpDebugTools
import com.adobe.clawdea.language.scala.ScalaPsiBridge
import com.adobe.clawdea.mcp.coexistence.CollidingToolNames
import com.adobe.clawdea.mcp.coexistence.JetBrainsMcpProbe
import com.adobe.clawdea.mcp.coexistence.JetBrainsMcpStatus
import com.adobe.clawdea.mcp.coexistence.applyCollisionFilter
import com.adobe.clawdea.profiling.analysis.AnalysisService
import com.adobe.clawdea.profiling.mcp.McpProfilingTools
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Project-level service that runs a local MCP HTTP server.
 * Starts on project open, stops on project close.
 * Exposes IntelliJ indices, diagnostics, and context as MCP tools.
 */
@Service(Service.Level.PROJECT)
class McpServer(private val project: Project) : Disposable {

    private val log = Logger.getInstance(McpServer::class.java)

    private var httpServer: HttpServer? = null
    private val router = McpToolRouter()
    private val dispatchExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "ClawDEA-MCP-dispatch").apply { isDaemon = true }
    }

    var port: Int = 0
        private set

    @Volatile
    var activeAutoAcceptEdits: Boolean = ClawDEASettings.getInstance().state.autoAcceptEdits

    @Volatile
    var activeToolApprovalMode: String = ClawDEASettings.getInstance().state.toolApprovalMode

    init {
        registerTools()
        start()
    }

    private fun registerTools() {
        McpIndexTools(project).registerAll(router)
        McpSearchTextTool(project).registerAll(router)
        McpIdeTools(project).registerAll(router)
        McpContextTool(project).registerAll(router)
        McpPrimerTool(project).registerAll(router)
        McpWikiTools(project).registerAll(router)
        McpWorkspaceTools(project).registerAll(router)            // Phase 3 — workspace tools
        McpEditReviewTools(project).registerAll(router)
        McpDebugTools(project).registerAll(router)
        McpProfilingTools(project, AnalysisService()).registerAll(router)
        registerScalaToolsIfAvailable()
        McpPermissionPromptTool(
            dispatcherResolver = { toolName, inputJson, toolUseId ->
                PermissionRouterRegistry.getInstance(project).route(toolName, inputJson, toolUseId)
            },
            toolApprovalModeSupplier = { activeToolApprovalMode },
            permissionPolicySupplier = {
                project.basePath?.let { basePath ->
                    PermissionPolicy {
                        ClaudePermissionSettingsReader(
                            projectBasePath = Path.of(basePath),
                        ).read()
                    }
                }
            },
            autoAllowNotifier = { toolName, inputJson, toolUseId ->
                AutoAllowSignal.getInstance(project).notify(toolName, inputJson, toolUseId)
            },
        ).registerAll(router)

        applyJetBrainsMcpCoexistence()
    }

    private fun applyJetBrainsMcpCoexistence() {
        val status = JetBrainsMcpProbe.forCurrentIde().status
        when (status) {
            JetBrainsMcpStatus.Enabled -> {
                applyCollisionFilter(router, status)
                log.info(
                    "JetBrains MCP plugin detected and enabled — deferring " +
                        "${CollidingToolNames.size} tools: $CollidingToolNames"
                )
            }
            JetBrainsMcpStatus.Unknown -> {
                log.warn(
                    "ClawDEA MCP: JetBrains plugin detected; setting probe failed " +
                        "(Unknown). Keeping ClawDEA tools registered."
                )
            }
            JetBrainsMcpStatus.Disabled -> {
                // no-op: ClawDEA registers its full surface as today.
            }
        }
    }

    /**
     * Registers [McpScalaTools] only when [ScalaPsiBridge] is available — i.e. the
     * IntelliJ Scala plugin is installed. When absent, the Scala tools simply do
     * not appear in `tools/list`. Some IntelliJ versions throw for unregistered
     * services rather than returning null, hence the try-catch.
     */
    private fun registerScalaToolsIfAvailable() {
        val bridge = try {
            ApplicationManager.getApplication()?.getService(ScalaPsiBridge::class.java)
        } catch (_: Throwable) {
            null
        } ?: return
        McpScalaTools(project, bridge).registerAll(router)
        log.info("Registered Scala-specific MCP tools (ScalaPsiBridge present)")
    }

    private fun start() {
        try {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = Executors.newCachedThreadPool { r ->
                Thread(r, "ClawDEA-MCP-handler").apply { isDaemon = true }
            }
            server.createContext("/mcp") { exchange -> handleRequest(exchange) }
            server.start()

            httpServer = server
            port = server.address.port

            log.info("MCP server started on 127.0.0.1:$port")
        } catch (e: Exception) {
            log.error("Failed to start MCP server", e)
        }
    }

    private fun handleRequest(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                sendResponse(exchange, 405, McpProtocol.errorResponse(null, -32600, "Only POST allowed"))
                return
            }

            val body = exchange.requestBody.bufferedReader().readText()
            val method = McpProtocol.parseJsonRpcMethod(body)
            val id = McpProtocol.parseJsonRpcId(body)

            log.info("MCP request: method=$method id=$id")

            val response = when (method) {
                "initialize" -> {
                    McpProtocol.initializeResponse(id ?: "null")
                }
                "notifications/initialized" -> {
                    // No-op notification — no response needed for notifications
                    sendResponse(exchange, 204, "")
                    return
                }
                "tools/list" -> {
                    val toolsJson = router.toolsListJson()
                    McpProtocol.toolsListResponse(id ?: "null", toolsJson)
                }
                "tools/call" -> {
                    val toolName = McpProtocol.parseToolName(body)
                    val arguments = McpProtocol.parseToolArguments(body)
                    log.info("tools/call id=$id tool=$toolName")
                    val startNanos = System.nanoTime()
                    val future = dispatchExecutor.submit<McpToolRouter.ToolResult> {
                        router.dispatch(toolName, arguments)
                    }
                    val result = try {
                        if (shouldUseGenericToolTimeout(toolName)) {
                            future.get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        } else {
                            future.get()
                        }
                    } catch (e: TimeoutException) {
                        future.cancel(true)
                        log.warn("MCP tool $toolName timed out after ${TOOL_TIMEOUT_SECONDS}s")
                        McpToolRouter.ToolResult("Tool timed out after ${TOOL_TIMEOUT_SECONDS}s", isError = true)
                    }
                    val durationMs = (System.nanoTime() - startNanos) / 1_000_000
                    val status = if (result.isError) "error" else "ok"
                    log.info("tools/call id=$id tool=$toolName $status duration=${durationMs}ms")
                    if (result.isError) {
                        log.warn("tools/call id=$id tool=$toolName error: ${result.text} | parsedArgs=$arguments | rawBody=$body")
                    }
                    McpProtocol.toolResultResponse(id ?: "null", result.text, result.isError)
                }
                else -> {
                    McpProtocol.errorResponse(id, McpProtocol.METHOD_NOT_FOUND, "Method not found: $method")
                }
            }

            sendResponse(exchange, 200, response)
        } catch (e: Exception) {
            log.warn("Error handling MCP request", e)
            try {
                sendResponse(exchange, 500, McpProtocol.errorResponse(null, McpProtocol.INTERNAL_ERROR, e.message ?: "Internal error"))
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(exchange: HttpExchange, statusCode: Int, body: String) {
        if (body.isEmpty()) {
            exchange.sendResponseHeaders(statusCode, -1)
            exchange.close()
            return
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun stop() {
        httpServer?.let { server ->
            server.stop(1)
            httpServer = null
            log.info("MCP server stopped")
        }
    }

    override fun dispose() {
        stop()
        dispatchExecutor.shutdownNow()
    }

    companion object {
        private const val TOOL_TIMEOUT_SECONDS = 60L
        private val USER_INTERACTIVE_TOOLS = setOf(
            "request_permission",
            "propose_edit",
            "propose_write",
            "propose_multi_edit",
            "propose_notebook_edit",
        )

        internal fun shouldUseGenericToolTimeout(toolName: String): Boolean =
            toolName !in USER_INTERACTIVE_TOOLS

        fun getInstance(project: Project): McpServer =
            project.getService(McpServer::class.java)
    }
}
