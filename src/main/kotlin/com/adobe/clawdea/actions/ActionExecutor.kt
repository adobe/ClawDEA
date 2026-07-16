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
package com.adobe.clawdea.actions

import com.adobe.clawdea.util.runReadAction

import com.adobe.clawdea.context.ContextEngine
import com.adobe.clawdea.context.ContextProfile
import com.adobe.clawdea.gateway.ClaudeGateway
import com.adobe.clawdea.gateway.GatewayRequest
import com.adobe.clawdea.gateway.StreamEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ActionExecutor {

    data class ActionResult(
        val text: String,
        val actionType: ActionType,
        val errorMessage: String? = null,
    )

    suspend fun execute(
        project: Project,
        editor: Editor,
        actionType: ActionType,
        selectedText: String,
        userInstructions: String? = null,
    ): ActionResult {
        val contextText = runReadAction {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            if (psiFile != null) {
                ContextEngine.getInstance(project).gatherContext(editor, psiFile, ContextProfile.ACTION)
            } else {
                ""
            }
        }

        val promptBuilder = ActionPromptBuilder()
        val prompt = promptBuilder.build(actionType, selectedText, contextText, userInstructions)

        val workingDir = project.basePath.orEmpty()
        val modelId = resolveActionModel(workingDir)
        val request = buildGatewayRequest(prompt, modelId)

        val result = StringBuilder()
        var errorMessage: String? = null
        withContext(Dispatchers.IO) {
            ClaudeGateway.getInstance().stream(request).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> result.append(event.text)
                    is StreamEvent.Error -> errorMessage = event.message
                    is StreamEvent.HttpError -> errorMessage = event.message
                    else -> {}
                }
            }
        }

        val responseText = result.toString().trim()
        val finalText = if (actionType.isCodeAction) {
            extractCodeFromResponse(responseText)
        } else {
            responseText
        }

        return ActionResult(finalText, actionType, errorMessage)
    }

    fun buildGatewayRequest(prompt: ActionPromptBuilder.ActionPrompt, modelId: String = "claude-sonnet-4-6"): GatewayRequest {
        val effectiveModel = modelId.ifBlank { "claude-sonnet-4-6" }
        return GatewayRequest(
            model = effectiveModel,
            maxTokens = 1024,
            systemPrompt = prompt.systemPrompt,
            userMessage = prompt.userMessage,
            timeoutSeconds = 30,
        )
    }

    private fun resolveActionModel(workingDirectory: String): String {
        val authManager = com.adobe.clawdea.auth.AuthManager.getInstance()
        val settings = com.adobe.clawdea.settings.ClawDEASettings.getInstance()
        val providerId = authManager.effectiveProviderId()

        val profileId = if (providerId == com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID) {
            settings.state.activeOpenAiCompatibleProfileId
        } else {
            ""
        }

        val catalogKey = com.adobe.clawdea.provider.ProviderRegistry.catalogKey(providerId, profileId)
        return settings.getSelectedModelId(workingDirectory, catalogKey)
    }

    fun extractCodeFromResponse(response: String): String {
        val fencePattern = Regex("```(?:\\w+)?\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val match = fencePattern.find(response)
        return if (match != null) {
            match.groupValues[1].trim()
        } else {
            response.trim()
        }
    }
}
