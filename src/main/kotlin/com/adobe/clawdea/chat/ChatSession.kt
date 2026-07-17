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

import com.adobe.clawdea.auth.CodexSubscriptionAuth
import com.adobe.clawdea.auth.CodexSubscriptionAuthEventListener
import com.adobe.clawdea.auth.SubscriptionAuth
import com.adobe.clawdea.auth.SubscriptionAuthEventListener
import com.adobe.clawdea.cli.CliBridge
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.gateway.ModelSelectorProbeStarter
import com.adobe.clawdea.mcp.McpServer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

class ChatSession(
    private val project: Project,
    val name: String = "Chat",
    private val autoResumeSessionId: String? = null,
    private val initialComposerDraft: String = "",
    selection: AgentSelection? = null,
) : Disposable {

    val bridge = CliBridge(
        workingDirectory = project.basePath ?: System.getProperty("user.home"),
        mcpPort = McpServer.getInstance(project).port,
        onAuthFailure = { reason ->
            // Route auth failures to the subscription subsystem for THIS TAB's provider selection,
            // not the global apiProvider. When an explicit per-tab selection is given, use it;
            // otherwise fall back to the effective provider (matching CliBridge's selection logic).
            val effectiveProvider = selection?.providerId
                ?: com.adobe.clawdea.auth.AuthManager.getInstance().effectiveProviderId()
            if (effectiveProvider == "openai-subscription") {
                CodexSubscriptionAuth.getInstance().invalidateCache()
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(CodexSubscriptionAuthEventListener.TOPIC)
                    .onAuthFailed(reason)
            } else {
                SubscriptionAuth.getInstance().invalidateCache()
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(SubscriptionAuthEventListener.TOPIC)
                    .onAuthFailed(reason)
            }
        },
        project = project,
        selection = selection,
    )
    val panel = ChatPanel(bridge, project, initialComposerDraft)

    init {
        Disposer.register(this, bridge)
        Disposer.register(this, panel)
        if (autoResumeSessionId != null && shouldAutoResume(autoResumeSessionId)) {
            panel.requestAutoResume(autoResumeSessionId)
        }
        panel.suggestSeedWikiIfMissing()
        ModelSelectorProbeStarter.probeIfApplicable(project)
    }

    /**
     * Gate auto-resume when it would carry a transcript ACROSS providers (a provider fallback).
     * ClawDEA never silently switches providers, so a cross-provider handoff involving the
     * OpenAI-compatible backend requires explicit user confirmation via [ProviderFallbackPrompt].
     * Same-provider resume, and pre-existing Claude/Codex switches, proceed unchanged.
     */
    private fun shouldAutoResume(sessionId: String): Boolean {
        val basePath = project.basePath ?: return true
        val origin = com.adobe.clawdea.chat.session.SessionCatalog.resolveOrigin(basePath, sessionId)
            ?: return true
        if (!ProviderFallbackPrompt.requiresConfirmation(origin, bridge.backendKind)) return true
        return ProviderFallbackPrompt(
            project = project,
            fromProvider = origin.displayLabel,
            toProvider = bridge.agentLabel,
        ).showAndGet()
    }

    override fun dispose() {}
}
