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

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * Detects whether the JetBrains-bundled MCP server plugin (`com.intellij.mcpServer`)
 * is currently exposing its tools.
 *
 * The probe is constructed with two seams to keep it pure-Kotlin testable:
 * - [pluginPresence] reports whether the plugin is installed and enabled.
 * - [settingsReader] returns the plugin's "server enabled" boolean, or throws on failure.
 *
 * Production wiring: see [forCurrentIde].
 */
class JetBrainsMcpProbe(
    private val pluginPresence: () -> PluginPresence,
    private val settingsReader: () -> Boolean,
) {

    enum class PluginPresence {
        NotInstalled,
        InstalledDisabled,
        InstalledEnabled,
    }

    val status: JetBrainsMcpStatus
        get() = when (pluginPresence()) {
            PluginPresence.NotInstalled, PluginPresence.InstalledDisabled ->
                JetBrainsMcpStatus.Disabled
            PluginPresence.InstalledEnabled -> try {
                if (settingsReader()) JetBrainsMcpStatus.Enabled else JetBrainsMcpStatus.Disabled
            } catch (_: Throwable) {
                JetBrainsMcpStatus.Unknown
            }
        }

    companion object {
        private const val PLUGIN_ID = "com.intellij.mcpServer"

        /**
         * Production factory. Resolves plugin presence through the public
         * [PluginManagerCore.isLoaded]/[PluginManagerCore.isDisabled] pair and
         * delegates the setting read to [JetBrainsMcpSettingsReader].
         *
         * Uses booleans rather than fetching the descriptor: descriptor lookups
         * (`getPlugin` / `findPlugin`) are `@ApiStatus.Internal` and return the
         * internal `IdeaPluginDescriptorImpl`, both of which fail the IntelliJ
         * Plugin Verifier on publish. The boolean pair fully expresses the
         * three-state [PluginPresence] without crossing into internal API.
         */
        fun forCurrentIde(): JetBrainsMcpProbe = JetBrainsMcpProbe(
            pluginPresence = {
                val pluginId = PluginId.getId(PLUGIN_ID)
                when {
                    PluginManagerCore.isLoaded(pluginId) -> PluginPresence.InstalledEnabled
                    PluginManagerCore.isDisabled(pluginId) -> PluginPresence.InstalledDisabled
                    else -> PluginPresence.NotInstalled
                }
            },
            settingsReader = { JetBrainsMcpSettingsReader.isServerEnabled() },
        )
    }
}
