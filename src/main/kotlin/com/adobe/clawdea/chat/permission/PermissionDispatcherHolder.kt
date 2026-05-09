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

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level holder for the [PermissionDispatcher]. Set by
 * [com.adobe.clawdea.chat.ChatPanel] during initialization; read by
 * [com.adobe.clawdea.mcp.McpServer] when registering the `request_permission`
 * tool. A single holder per project so the MCP server (also project-level)
 * does not need to know about the panel.
 *
 * Returns a safe "always-deny" dispatcher when no panel is registered yet —
 * this happens when the MCP server starts before the chat panel is open,
 * and we must not deadlock the CLI on a null dispatcher.
 */
@Service(Service.Level.PROJECT)
class PermissionDispatcherHolder(@Suppress("unused") private val project: Project? = null) {

    @Volatile
    private var dispatcher: PermissionDispatcher? = null

    fun set(dispatcher: PermissionDispatcher) {
        this.dispatcher = dispatcher
    }

    fun clear() {
        this.dispatcher = null
    }

    fun clear(dispatcher: PermissionDispatcher) {
        if (this.dispatcher === dispatcher) {
            this.dispatcher = null
        }
    }

    fun get(): PermissionDispatcher = dispatcher ?: FALLBACK

    companion object {
        fun getInstance(project: Project): PermissionDispatcherHolder =
            project.getService(PermissionDispatcherHolder::class.java)

        /** Denies without blocking when no UI is active. */
        private val FALLBACK = PermissionDispatcher(
            onRender = { req -> req.resolve(PermissionRequest.Decision.DENY) },
        )
    }
}
