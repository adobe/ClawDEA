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
package com.adobe.clawdea.chat.permission

import com.adobe.clawdea.chat.ChatBrowserRenderer
import com.adobe.clawdea.mcp.McpPermissionPromptTool
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.jcef.JBCefJSQuery

/**
 * Wires the permission-card JCEF bridge into the chat view.
 *
 * Two flows:
 *  1. **Render**: [onRender] is invoked by [PermissionDispatcher] when a new
 *     request is submitted. Runs on the MCP dispatch thread. We append a card
 *     to the chat transcript via [browserRenderer]. AskUserQuestion gets a
 *     dedicated multi-choice card; everything else gets the standard
 *     Allow/Deny card.
 *  2. **Decide**: JS posts `"<requestId>:<action>[:<payload>]"` through the
 *     [JBCefJSQuery] bridge. Recognised actions:
 *       - `allow` / `deny` — regular permission card
 *       - `submit` with a JSON payload of answers — AskUserQuestion card
 *       - `cancel` — AskUserQuestion card
 */
class PermissionRequestHandler(
    private val dispatcher: PermissionDispatcher,
    private val renderer: PermissionRequestRenderer,
    private val questionRenderer: AskUserQuestionRenderer,
    private val browserRenderer: ChatBrowserRenderer,
    private val settingsWriter: ClaudePermissionSettingsWriter? = null,
) {

    /** Called from the MCP dispatch thread when a new permission prompt is needed. */
    val onRender: (PermissionRequest) -> Unit = { request ->
        val html = renderRequest(request)
        ApplicationManager.getApplication().invokeLater {
            browserRenderer.appendHtml(html)
        }
    }

    /**
     * Called from the MCP dispatch thread when a request was silently allowed
     * (e.g. under "Allow all"). We render a compact notice so the user sees
     * what ran, but no buttons — there's nothing left to decide.
     */
    val onAutoAllowed: (PermissionRequest) -> Unit = { request ->
        val html = renderer.renderAutoAllowedNotice(request)
        ApplicationManager.getApplication().invokeLater {
            browserRenderer.appendHtml(html)
        }
    }

    /** Installs the JCEF bridge handler on the given query. */
    fun install(permissionDecisionQuery: JBCefJSQuery) {
        permissionDecisionQuery.addHandler { payload ->
            val parts = payload.split(":", limit = 3)
            if (parts.size >= 2) {
                val requestId = parts[0]
                val action = parts[1].lowercase()
                val data = parts.getOrNull(2).orEmpty()
                ApplicationManager.getApplication().invokeLater {
                    handleAction(requestId, action, data)
                }
            }
            JBCefJSQuery.Response("ok")
        }
    }

    private fun handleAction(requestId: String, action: String, data: String) {
        when (action) {
            "allow" -> {
                resolveStandardRequest(requestId, "Allowed", PermissionRequest.Decision.ALLOW)
            }
            "deny" -> {
                resolveStandardRequest(requestId, "Denied", PermissionRequest.Decision.DENY)
            }
            "always" -> {
                browserRenderer.executeJavaScript(renderer.buildScopePickerScript(requestId))
            }
            "always-scope" -> {
                handleAlwaysAllow(requestId, data)
            }
            "submit" -> {
                handleQuestionSubmit(requestId, data)
            }
            "cancel" -> {
                browserRenderer.executeJavaScript(
                    questionRenderer.buildResolvedScript(requestId, emptyMap(), skipped = true),
                )
                dispatcher.resolve(requestId, PermissionRequest.Decision.DENY)
            }
        }
    }

    private fun renderRequest(request: PermissionRequest): String {
        if (request.toolName != McpPermissionPromptTool.ASK_USER_QUESTION) {
            return renderer.renderCard(request)
        }
        val input = AskUserQuestionInput.parse(request.inputJson) ?: return renderer.renderCard(request)
        return questionRenderer.renderCard(request.requestId, input)
    }

    private fun resolveStandardRequest(
        requestId: String,
        label: String,
        decision: PermissionRequest.Decision,
    ) {
        browserRenderer.executeJavaScript(renderer.buildDecisionScript(requestId, label))
        dispatcher.resolve(requestId, decision)
    }

    private fun handleQuestionSubmit(requestId: String, data: String) {
        val request = dispatcher.peek(requestId)
        val answers = parseAnswers(data)
        val updatedInput = AskUserQuestionInput.buildUpdatedInput(
            request?.inputJson.orEmpty(),
            answers,
        )
        browserRenderer.executeJavaScript(
            questionRenderer.buildResolvedScript(requestId, answers, skipped = false),
        )
        dispatcher.resolve(requestId, PermissionRequest.Decision.ALLOW, updatedInput)
    }

    private fun handleAlwaysAllow(requestId: String, scope: String) {
        val request = dispatcher.peek(requestId)
        if (request == null) {
            browserRenderer.executeJavaScript(renderer.buildWarningScript(requestId, "Permission request is no longer active."))
            return
        }
        val writer = settingsWriter
        if (writer == null) {
            browserRenderer.executeJavaScript(
                renderer.buildWarningScript(requestId, "Could not persist Claude settings for this project."),
            )
            return
        }
        val rule = buildAllowRule(request, scope)
        val result = writer.appendAllowRule(rule)
        if (!result.success) {
            val message = result.message ?: "Could not update Claude settings."
            browserRenderer.executeJavaScript(renderer.buildWarningScript(requestId, message))
            return
        }
        browserRenderer.executeJavaScript(renderer.buildDecisionScript(requestId, "Always allowed"))
        dispatcher.resolve(requestId, PermissionRequest.Decision.ALLOW)
    }

    private fun buildAllowRule(request: PermissionRequest, scope: String): String {
        val input = PermissionToolInput.extractSpecifier(request.toolName, request.inputJson)
        return when (scope) {
            "exact" -> input?.let { "${request.toolName}($it)" } ?: request.toolName
            "similar" -> input?.let { "${request.toolName}(${similarPattern(request.toolName, it)})" } ?: request.toolName
            else -> request.toolName
        }
    }

    private fun similarPattern(toolName: String, input: String): String {
        if (toolName != "Bash") return input
        val firstToken = input.trim().substringBefore(" ")
        return if (firstToken.isBlank()) input else "$firstToken *"
    }

    private fun parseAnswers(data: String): Map<String, String> {
        if (data.isBlank()) return emptyMap()
        return try {
            val root = JsonParser.parseString(data)
            if (!root.isJsonObject) return emptyMap()
            val obj: JsonObject = root.asJsonObject
            buildMap {
                for ((key, value) in obj.entrySet()) {
                    if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                        put(key, value.asString)
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
