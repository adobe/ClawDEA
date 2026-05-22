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
 *
 * **Late AskUserQuestion answers.** When a multi-choice prompt sits long
 * enough for [PermissionDispatcher.submit] to time out (claude-code #50289
 * caps every HTTP MCP call at ~60 s), the CLI has already finalised the
 * tool call as denied and emitted a Result that ends Claude's turn
 * ("I'm waiting for you to approve in the IDE"). The `pendingDecisions`
 * cache parks the answer for a hypothetical retry of the same
 * AskUserQuestion call, but in practice Claude does not retry on its own
 * — and an `AskUserQuestion` answer is data only the user can produce,
 * so a "continue" from the user just makes Claude re-ask. To unblock
 * the conversation we feed the answer back as a synthetic user message
 * via [onLateAnswer] when `dispatcher.resolve` reports the resolution
 * was late. This kicks off a fresh turn carrying the user's selection.
 */
class PermissionRequestHandler(
    private val dispatcher: PermissionDispatcher,
    private val renderer: PermissionRequestRenderer,
    private val questionRenderer: AskUserQuestionRenderer,
    private val browserRenderer: ChatBrowserRenderer,
    private val settingsWriter: ClaudePermissionSettingsWriter? = null,
    private val onLateAnswer: (String) -> Unit = {},
) {

    /**
     * Called from the MCP dispatch thread when a new permission prompt is needed.
     *
     * When the request carries a `toolUseId`, the card is injected *inside* the
     * matching tool block via `data-tool-id` so the prompt visually attaches to
     * the tool it gates and routes to the correct ChatPanel by construction
     * (the tool block only exists in the panel that received the ToolUse).
     *
     * AskUserQuestion suppresses its own tool block (see EventStreamHandler), so
     * its `toolUseId` will not match any DOM element. The same is true when the
     * router didn't have a `toolUseId` to set. Both fall back to `appendHtml`.
     */
    val onRender: (PermissionRequest) -> Unit = { request ->
        val html = renderRequest(request)
        val toolUseId = request.toolUseId
        ApplicationManager.getApplication().invokeLater {
            if (toolUseId != null && request.toolName != McpPermissionPromptTool.ASK_USER_QUESTION) {
                browserRenderer.injectToolAttachment(toolUseId, html)
            } else {
                browserRenderer.appendHtml(html)
            }
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
                handleQuestionCancel(requestId)
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
        val late = dispatcher.resolve(requestId, PermissionRequest.Decision.ALLOW, updatedInput)
        if (late && answers.isNotEmpty()) {
            onLateAnswer(buildLateAnswerMessage(answers))
        }
    }

    private fun handleQuestionCancel(requestId: String) {
        browserRenderer.executeJavaScript(
            questionRenderer.buildResolvedScript(requestId, emptyMap(), skipped = true),
        )
        val late = dispatcher.resolve(requestId, PermissionRequest.Decision.DENY)
        if (late) {
            onLateAnswer(LATE_SKIP_MESSAGE)
        }
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

    companion object {
        /**
         * Parse the `answers` map from a question-submit payload.
         *
         * Tolerates two wire shapes:
         *  1. The new shape `{ "answers": { ... }, "freeforms": { ... } }`
         *     produced by `window.collectQuestionAnswers` once the freeform
         *     work in Task 19a lands.
         *  2. The legacy flat shape `{ "<question>": "<label>", ... }`
         *     previously posted directly by the JS bridge — kept for
         *     backwards compatibility with the existing CLI path and any
         *     test fixtures.
         *
         * `internal` rather than `private` so the parser tests can exercise
         * both shapes without going through JCEF / the Application thread.
         * Task 19b will broaden the visibility (or wrap it) when a real
         * consumer wires up.
         */
        internal fun parseAnswers(data: String): Map<String, String> {
            if (data.isBlank()) return emptyMap()
            return try {
                val root = JsonParser.parseString(data)
                if (!root.isJsonObject) return emptyMap()
                val obj: JsonObject = root.asJsonObject
                val source = obj.get("answers")?.takeIf { it.isJsonObject }?.asJsonObject ?: obj
                stringMapOf(source)
            } catch (_: Exception) {
                emptyMap()
            }
        }

        /**
         * Parse the `freeforms` map from a question-submit payload. Returns
         * an empty map for the legacy flat shape (no `freeforms` field).
         *
         * `internal` for testing; Task 19b will introduce the first real
         * consumer (sibling code path to `handleQuestionSubmit`).
         */
        internal fun parseFreeforms(data: String): Map<String, String> {
            if (data.isBlank()) return emptyMap()
            return try {
                val root = JsonParser.parseString(data)
                if (!root.isJsonObject) return emptyMap()
                val obj: JsonObject = root.asJsonObject
                val source = obj.get("freeforms")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return emptyMap()
                stringMapOf(source)
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun stringMapOf(obj: JsonObject): Map<String, String> = buildMap {
            for ((key, value) in obj.entrySet()) {
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    put(key, value.asString)
                }
            }
        }

        /**
         * Synthetic user message used when the user clicks Skip on an
         * AskUserQuestion card after the prompt-tool round-trip already
         * timed out. Tells Claude to proceed without an answer rather
         * than re-asking.
         */
        internal const val LATE_SKIP_MESSAGE: String =
            "I skipped the multiple-choice question you asked earlier (the prompt-tool round-trip timed out before I could respond). Please continue without that input."

        /**
         * Synthetic user message we feed back into the CLI when an
         * AskUserQuestion is answered after [PermissionDispatcher.submit]
         * has already given up. Phrased so Claude treats the body as the
         * answer to the question it asked rather than as a fresh prompt,
         * and explicitly notes the prompt-tool round-trip timed out so
         * the model does not re-issue the same AskUserQuestion call.
         */
        internal fun buildLateAnswerMessage(answers: Map<String, String>): String = buildString {
            append("My answer to the multiple-choice question you asked earlier")
            append(" (the prompt-tool round-trip timed out before I could submit, so this arrives as a follow-up message instead of a tool result):\n")
            for ((q, a) in answers) {
                if (q.isBlank() || a.isBlank()) continue
                append("- ").append(q).append(" → ").append(a).append('\n')
            }
            append("\nPlease continue from here using these answers.")
        }
    }
}
