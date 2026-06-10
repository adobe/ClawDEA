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

import com.adobe.clawdea.auth.SubscriptionAuth
import com.adobe.clawdea.auth.SubscriptionAuthEventListener
import com.adobe.clawdea.cli.CliBridge
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
) : Disposable {

    val bridge = CliBridge(
        project.basePath ?: System.getProperty("user.home"),
        McpServer.getInstance(project).port,
        onAuthFailure = { reason ->
            SubscriptionAuth.getInstance().invalidateCache()
            ApplicationManager.getApplication().messageBus
                .syncPublisher(SubscriptionAuthEventListener.TOPIC)
                .onAuthFailed(reason)
        },
        project = project,
    )
    val panel = ChatPanel(bridge, project)

    init {
        Disposer.register(this, bridge)
        Disposer.register(this, panel)
        if (autoResumeSessionId != null) {
            panel.requestAutoResume(autoResumeSessionId)
        }
        panel.suggestSeedWikiIfMissing()
        ModelSelectorProbeStarter.probeIfApplicable(project)
    }

    override fun dispose() {}
}
