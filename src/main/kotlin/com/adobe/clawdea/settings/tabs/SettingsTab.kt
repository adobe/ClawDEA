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
// src/main/kotlin/com/adobe/clawdea/settings/tabs/SettingsTab.kt
package com.adobe.clawdea.settings.tabs

import com.adobe.clawdea.settings.ClawDEASettings
import javax.swing.JComponent

/**
 * Contract for a single settings tab.
 *
 * Each tab owns a disjoint set of fields and implements the persistence triple
 * ([loadFrom]/[applyTo]/[isModifiedFrom]) for ONLY the fields it renders. The
 * [com.adobe.clawdea.settings.ClawDEASettingsConfigurable] aggregates these:
 * `isModified = tabs.any { it.isModifiedFrom(state) }`, and `apply`/`reset` fan
 * out to every tab. Field persistence semantics are identical to the previous
 * flat panel — a field reads/writes the same [ClawDEASettings.State] property as
 * before; only its host tab changed.
 */
interface SettingsTab {
    /** Title shown on the tab. */
    val title: String

    /** The Swing component rendered inside the tab. */
    val component: JComponent

    /** Populate this tab's fields from persisted [state]. */
    fun loadFrom(state: ClawDEASettings.State)

    /** Write this tab's fields into [state]. */
    fun applyTo(state: ClawDEASettings.State)

    /** True when any of this tab's fields differ from persisted [state]. */
    fun isModifiedFrom(state: ClawDEASettings.State): Boolean
}
