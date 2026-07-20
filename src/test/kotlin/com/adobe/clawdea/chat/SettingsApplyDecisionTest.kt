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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A settings apply must NEVER change an already-open chat tab's provider/backend. The tab keeps the
 * per-tab AgentSelection it was built with; only NEW chats pick up a changed global default. This
 * guards the regression where setting Roles → Chat default (or any settings apply) rebuilt an open
 * Claude/Opus tab onto the global provider (e.g. Qwen).
 */
class SettingsApplyDecisionTest {

    @Test
    fun `running idle tab restarts in place to pick up non-provider settings`() {
        assertEquals(
            SettingsApplyAction.RESTART_IN_PLACE,
            settingsApplyAction(bridgeRunning = true, isStreaming = false),
        )
    }

    @Test
    fun `streaming tab is left untouched`() {
        assertEquals(
            SettingsApplyAction.NONE,
            settingsApplyAction(bridgeRunning = true, isStreaming = true),
        )
    }

    @Test
    fun `not-running tab is left untouched`() {
        assertEquals(
            SettingsApplyAction.NONE,
            settingsApplyAction(bridgeRunning = false, isStreaming = false),
        )
    }
}
