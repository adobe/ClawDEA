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

import com.adobe.clawdea.chat.session.ChatAutoResumeState
import com.adobe.clawdea.chat.session.SessionCatalog
import com.adobe.clawdea.provider.AgentRole
import com.adobe.clawdea.provider.RoleSelectionStore
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        addNewSession(project, toolWindow, "Chat")

        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (toolWindow.contentManager.contentCount == 0) {
                    addNewSession(project, toolWindow, "Chat")
                }
            }
        })

        toolWindow.setTitleActions(listOf(object : AnAction("New Chat", "Open a new chat session", com.intellij.icons.AllIcons.General.Add), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                val tabCount = toolWindow.contentManager.contentCount + 1
                addNewSession(project, toolWindow, "Chat $tabCount")
            }
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
        }))
    }

    companion object {
        fun addNewSession(project: Project, toolWindow: ToolWindow, name: String) {
            val state = project.getService(ChatAutoResumeState::class.java)
            val resumeId = if (state.consumeIfUnused()) {
                project.basePath?.let { SessionCatalog.mostRecent(it)?.id }
            } else {
                null
            }

            // New tabs seed their selection from the CHAT_DEFAULT role.
            val selection = RoleSelectionStore(ClawDEASettings.getInstance()).get(AgentRole.CHAT_DEFAULT)

            val session = ChatSession(
                project,
                name,
                autoResumeSessionId = resumeId,
                selection = selection,
            )
            val content = ContentFactory.getInstance().createContent(
                session.panel,
                name,
                true,
            )
            content.setDisposer(session)
            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content)
        }
    }
}
