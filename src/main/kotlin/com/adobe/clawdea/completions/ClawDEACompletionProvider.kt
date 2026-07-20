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
// src/main/kotlin/com/adobe/clawdea/completions/ClawDEACompletionProvider.kt
package com.adobe.clawdea.completions

import com.adobe.clawdea.util.runReadAction

import com.adobe.clawdea.context.ContextEngine
import com.adobe.clawdea.context.ContextProfile
import com.adobe.clawdea.gateway.ClaudeGateway
import com.adobe.clawdea.gateway.GatewayRequest
import com.adobe.clawdea.gateway.StreamEvent
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.psi.PsiDocumentManager
import kotlin.time.Duration.Companion.milliseconds

/**
 * Provides inline code completions via the Claude API.
 *
 * Uses IntelliJ's DebouncedInlineCompletionProvider which handles:
 * - Debouncing (delays the request until typing pauses)
 * - Ghost text rendering (InlayModel)
 * - Tab to accept, Escape to dismiss
 * - Cancellation on continued typing
 *
 * We provide:
 * - Context gathering via ContextEngine (COMPLETION profile)
 * - Prompt construction via CompletionPromptBuilder
 * - API call via ClaudeGateway
 */
class ClawDEACompletionProvider : DebouncedInlineCompletionProvider() {

    override val id = InlineCompletionProviderID("ClawDEA")

    private val promptBuilder = CompletionPromptBuilder()

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val settings = ClawDEASettings.getInstance()
        if (!settings.state.completionsEnabled) return false
        // Manual-only mode: suppress automatic (typing/caret) triggers so we
        // only spend tokens when the user explicitly asks for a completion via
        // the hotkey/action. See issue #146.
        if (settings.state.completionsManualOnly && !isManualTrigger(event)) return false

        // Gate on the COMPLETIONS role selection, not the global provider. The completion
        // EXECUTES via RoleSelectionStore.get(COMPLETIONS) (ClaudeGateway, T8); the gate must
        // consult the same provider so it can't offer/suppress against a different one.
        val authManager = com.adobe.clawdea.auth.AuthManager.getInstance()
        val completionsSelection = com.adobe.clawdea.provider.RoleSelectionStore(settings)
            .get(com.adobe.clawdea.provider.AgentRole.COMPLETIONS)
        val providerId = completionsSelection.providerId
        val descriptor = com.adobe.clawdea.provider.ProviderRegistry.require(providerId)
        val providerConfigured = authManager.isAuthenticated(completionsSelection)

        val selectedModelId = resolveCompletionsGateModelId(completionsSelection) { catalogKey ->
            settings.getSelectedModelId("", catalogKey)
        }

        return isProviderCompletionEnabled(
            providerId = providerId,
            supportsInlineCompletions = descriptor.supportsInlineCompletions,
            providerConfigured = providerConfigured,
            selectedModelId = selectedModelId,
        )
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): kotlin.time.Duration {
        return ClawDEASettings.getInstance().state.completionsDebounceMs.milliseconds
    }

    override suspend fun getSuggestionDebounced(
        request: InlineCompletionRequest
    ): InlineCompletionSuggestion {
        val editor = request.editor
        val project = editor.project ?: return EMPTY_SUGGESTION

        val documentText = runReadAction { request.document.text }
        val offset = request.endOffset

        val needsContext = CompletionPromptBuilder.needsSemanticContext(documentText, offset)

        val contextText = if (needsContext) {
            runReadAction {
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(request.document)
                if (psiFile != null) {
                    ContextEngine.getInstance(project).gatherContext(editor, psiFile, ContextProfile.COMPLETION)
                } else {
                    ""
                }
            }
        } else {
            ""
        }

        val prompt = promptBuilder.build(documentText, offset, contextText)

        val settings = ClawDEASettings.getInstance()
        val completionsSelection = com.adobe.clawdea.provider.RoleSelectionStore(settings)
            .get(com.adobe.clawdea.provider.AgentRole.COMPLETIONS)
        val completionsModel = if (completionsSelection.modelId.isNotBlank()) {
            resolveCompletionModel(completionsSelection.modelId)
        } else {
            // Fallback to legacy model setting if role selection has no model
            resolveCompletionModel(settings.state.completionsModel)
        }

        val gatewayRequest = GatewayRequest(
            model = completionsModel,
            maxTokens = 128,
            systemPrompt = prompt.systemPrompt,
            userMessage = prompt.userMessage,
            timeoutSeconds = 8,
            applyCompletionsRole = true,
        )

        val result = StringBuilder()
        ClaudeGateway.getInstance().stream(gatewayRequest).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> result.append(event.text)
                is StreamEvent.Error -> {} // Silently skip — stale by the time a retry would succeed
                is StreamEvent.HttpError -> {} // Silently skip — stale by the time a retry would succeed
                else -> {}
            }
        }

        val currentLine = runReadAction {
            val lineNumber = request.document.getLineNumber(offset)
            val lineStart = request.document.getLineStartOffset(lineNumber)
            request.document.getText(com.intellij.openapi.util.TextRange(lineStart, offset))
        }
        val completionText = CompletionSanitizer.sanitize(result.toString(), currentLine)
        if (completionText.isBlank()) return EMPTY_SUGGESTION

        return InlineCompletionSingleSuggestion.build { data ->
            emit(InlineCompletionGrayTextElement(completionText))
        }
    }

    companion object {
        private val EMPTY_SUGGESTION = InlineCompletionSingleSuggestion.build { _ -> }

        /**
         * Pure gating logic for provider completion enablement.
         * Extracted for testability — no IntelliJ dependencies.
         *
         * @param providerId The resolved provider id (e.g. "anthropic", "openai-compatible")
         * @param supportsInlineCompletions Whether the provider descriptor supports inline completions
         * @param providerConfigured Whether the provider has valid credentials/configuration
         * @param selectedModelId The resolved model id for this provider ("" if none)
         * @return true if the provider is ready to serve completions, false otherwise
         */
        internal fun isProviderCompletionEnabled(
            providerId: String,
            supportsInlineCompletions: Boolean,
            providerConfigured: Boolean,
            selectedModelId: String,
        ): Boolean {
            if (!supportsInlineCompletions) return false
            if (!providerConfigured) return false

            // For openai-compatible, require a non-blank selected model.
            // For existing Claude providers (anthropic, bedrock, subscription, vertex),
            // allow blank model (they fall back to CLI defaults).
            if (providerId == com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID) {
                if (selectedModelId.isBlank()) return false
            }

            return true
        }

        /**
         * Resolves the model id used by the completions enablement gate for the given COMPLETIONS
         * [selection]. Pure/testable (no IntelliJ deps): for openai-compatible it uses the selection's
         * own model, falling back to the profile's stored model (via [storedModelLookup] keyed by the
         * catalog key) when the selection's model is blank; for Claude providers it returns "" (they
         * fall back to CLI defaults, so no model is required to enable completions).
         */
        internal fun resolveCompletionsGateModelId(
            selection: com.adobe.clawdea.provider.AgentSelection,
            storedModelLookup: (catalogKey: String) -> String,
        ): String {
            if (selection.providerId != com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID) {
                return ""
            }
            return selection.modelId.ifBlank {
                val catalogKey = com.adobe.clawdea.provider.ProviderRegistry.catalogKey(
                    selection.providerId,
                    selection.profileId.orEmpty(),
                )
                storedModelLookup(catalogKey)
            }
        }

        /**
         * True when [event] originates from an explicit user request rather than
         * incidental editor activity. `DirectCall` is our own action / hotkey
         * (see [com.adobe.clawdea.completions.TriggerCompletionAction]);
         * `ManualCall` is the platform's built-in "Call Inline Completion".
         * Everything else (document changes, caret moves, editor focus, lookup)
         * is automatic and must be suppressed in manual-only mode.
         */
        fun isManualTrigger(event: InlineCompletionEvent): Boolean =
            event is InlineCompletionEvent.DirectCall ||
                event is InlineCompletionEvent.ManualCall

        private val MODEL_MAP = mapOf(
            "haiku" to "claude-haiku-4-5",
            "sonnet" to "claude-sonnet-4-6",
        )

        fun resolveCompletionModel(setting: String): String =
            MODEL_MAP[setting] ?: MODEL_MAP["sonnet"]!!
    }
}
