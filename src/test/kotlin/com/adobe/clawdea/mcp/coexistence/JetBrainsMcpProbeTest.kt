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

import org.junit.Assert.assertEquals
import org.junit.Test

class JetBrainsMcpProbeTest {

    @Test
    fun `plugin not installed yields Disabled`() {
        val probe = JetBrainsMcpProbe(
            pluginPresence = { JetBrainsMcpProbe.PluginPresence.NotInstalled },
            settingsReader = { error("must not be called") },
        )
        assertEquals(JetBrainsMcpStatus.Disabled, probe.status)
    }

    @Test
    fun `plugin installed but disabled yields Disabled`() {
        val probe = JetBrainsMcpProbe(
            pluginPresence = { JetBrainsMcpProbe.PluginPresence.InstalledDisabled },
            settingsReader = { error("must not be called") },
        )
        assertEquals(JetBrainsMcpStatus.Disabled, probe.status)
    }

    @Test
    fun `plugin enabled and setting on yields Enabled`() {
        val probe = JetBrainsMcpProbe(
            pluginPresence = { JetBrainsMcpProbe.PluginPresence.InstalledEnabled },
            settingsReader = { true },
        )
        assertEquals(JetBrainsMcpStatus.Enabled, probe.status)
    }

    @Test
    fun `plugin enabled and setting off yields Disabled`() {
        val probe = JetBrainsMcpProbe(
            pluginPresence = { JetBrainsMcpProbe.PluginPresence.InstalledEnabled },
            settingsReader = { false },
        )
        assertEquals(JetBrainsMcpStatus.Disabled, probe.status)
    }

    @Test
    fun `plugin enabled and settings reader throws yields Unknown`() {
        val probe = JetBrainsMcpProbe(
            pluginPresence = { JetBrainsMcpProbe.PluginPresence.InstalledEnabled },
            settingsReader = { throw IllegalStateException("reflection broke") },
        )
        assertEquals(JetBrainsMcpStatus.Unknown, probe.status)
    }
}
