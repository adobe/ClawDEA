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
package com.adobe.clawdea.mcp.coexistence

import com.intellij.openapi.application.ApplicationManager

/**
 * Reads the JetBrains MCP plugin's "server enabled" setting via reflection.
 *
 * Throws on any failure (class not found, property missing, wrong type, security).
 * The caller maps the throw to [JetBrainsMcpStatus.Unknown] so ClawDEA fails open.
 */
object JetBrainsMcpSettingsReader {

    // Confirmed in Task 5 step 1 against the bundled JetBrains MCP plugin.
    // Update both constants together if the JetBrains team renames either.
    private const val SETTINGS_CLASS_FQCN = "com.intellij.mcpserver.settings.McpServerSettings"
    private const val STATE_CLASS_FQCN = "com.intellij.mcpserver.settings.McpServerSettings\$MyState"
    private const val ENABLED_PROPERTY_NAME = "enableMcpServer"

    /**
     * @return true if the JetBrains MCP server is currently enabled.
     * @throws Throwable when the underlying setting cannot be read; the caller
     *   is expected to map the throw to [JetBrainsMcpStatus.Unknown].
     */
    fun isServerEnabled(): Boolean {
        val settingsClass = Class.forName(SETTINGS_CLASS_FQCN)
        val stateClass = Class.forName(STATE_CLASS_FQCN)
        val service = ApplicationManager.getApplication().getService(settingsClass)
            ?: throw IllegalStateException("Service not registered: $SETTINGS_CLASS_FQCN")

        // Get the state object: McpServerSettings extends SimplePersistentStateComponent<MyState>
        val getStateMethod = settingsClass.getMethod("getState")
        val stateObject = getStateMethod.invoke(service)
            ?: throw IllegalStateException("getState() returned null")

        // Read the enableMcpServer property from MyState
        val getterName = "get" + ENABLED_PROPERTY_NAME.replaceFirstChar { it.uppercaseChar() }
        val getter = stateClass.getMethod(getterName)
        return getter.invoke(stateObject) as Boolean
    }
}
