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
package com.adobe.clawdea.settings.tabs

import com.adobe.clawdea.settings.ClawDEASettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the pure-state tabs (no service / PasswordSafe access),
 * asserting the load/apply/isModified triple is preserved after the flat panel
 * was split into per-tab panels.
 */
class SettingsTabRoundTripTest {

    @Test
    fun `knowledge tab round-trips every field`() {
        val state = ClawDEASettings.State().apply {
            enableKnowledgeLayer = false
            enableWikiLibrarian = false
            enableWorkspace = false
            autoUpdateWiki = true
        }

        val a = KnowledgeLayerTab()
        a.loadFrom(state)
        assertFalse("no edit → not modified", a.isModifiedFrom(state))

        // Edit one field via a fresh state comparison: flip in the loaded tab.
        val edited = ClawDEASettings.State().apply {
            enableKnowledgeLayer = true
            enableWikiLibrarian = true
            enableWorkspace = true
            autoUpdateWiki = false
        }
        a.loadFrom(edited)
        assertFalse(a.isModifiedFrom(edited))
        assertTrue("differs from original state → modified", a.isModifiedFrom(state))

        // apply into a blank state and reload into a new instance: equal.
        val out = ClawDEASettings.State()
        a.applyTo(out)
        val b = KnowledgeLayerTab()
        b.loadFrom(out)
        assertFalse(b.isModifiedFrom(out))
        assertEquals(edited.enableKnowledgeLayer, out.enableKnowledgeLayer)
        assertEquals(edited.enableWikiLibrarian, out.enableWikiLibrarian)
        assertEquals(edited.enableWorkspace, out.enableWorkspace)
        assertEquals(edited.autoUpdateWiki, out.autoUpdateWiki)
    }

    @Test
    fun `profiling tab round-trips every field`() {
        val state = ClawDEASettings.State().apply {
            profilingBackendPreference = "jfr"
            profilingSamplingIntervalMs = 25
            profilingMaxDurationSeconds = 111
            profilingMaxRecordingMb = 222
            profilingStackDepth = 64
            profilingMaxRecordings = 7
            profilingMaxStorageGb = 3
            profilingAutoAnalyze = false
            profilingTopN = 42
        }

        val tab = ProfilingTab()
        tab.loadFrom(state)
        assertFalse(tab.isModifiedFrom(state))

        val out = ClawDEASettings.State()
        tab.applyTo(out)
        assertEquals("jfr", out.profilingBackendPreference)
        assertEquals(25, out.profilingSamplingIntervalMs)
        assertEquals(111, out.profilingMaxDurationSeconds)
        assertEquals(222, out.profilingMaxRecordingMb)
        assertEquals(64, out.profilingStackDepth)
        assertEquals(7, out.profilingMaxRecordings)
        assertEquals(3, out.profilingMaxStorageGb)
        assertFalse(out.profilingAutoAnalyze)
        assertEquals(42, out.profilingTopN)

        val reloaded = ProfilingTab()
        reloaded.loadFrom(out)
        assertFalse(reloaded.isModifiedFrom(out))
        // Modified after an edit relative to the default state.
        assertTrue(reloaded.isModifiedFrom(ClawDEASettings.State()))
    }

    @Test
    fun `advanced tab round-trips every field`() {
        val state = ClawDEASettings.State().apply {
            completionTokenBudget = 999
            chatTokenBudget = 12345
            actionTokenBudget = 321
            cliExtraArgs = "--foo bar"
            cliEnvScript = "/tmp/env.sh"
            enablePsiCollector = false
            enableGitCollector = false
            preloadSkillCatalog = false
            enableBaselineDefaults = false
            gatewayBareMode = false
        }

        val tab = AdvancedTab()
        tab.loadFrom(state)
        assertFalse(tab.isModifiedFrom(state))

        val out = ClawDEASettings.State()
        tab.applyTo(out)
        assertEquals(999, out.completionTokenBudget)
        assertEquals(12345, out.chatTokenBudget)
        assertEquals(321, out.actionTokenBudget)
        assertEquals("--foo bar", out.cliExtraArgs)
        assertEquals("/tmp/env.sh", out.cliEnvScript)
        assertFalse(out.enablePsiCollector)
        assertFalse(out.enableGitCollector)
        assertFalse(out.preloadSkillCatalog)
        assertFalse(out.enableBaselineDefaults)
        assertFalse(out.gatewayBareMode)

        val reloaded = AdvancedTab()
        reloaded.loadFrom(out)
        assertFalse(reloaded.isModifiedFrom(out))
        assertTrue(reloaded.isModifiedFrom(ClawDEASettings.State()))
    }
}
