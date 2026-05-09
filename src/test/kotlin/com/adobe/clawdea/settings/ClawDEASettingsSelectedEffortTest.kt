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
package com.adobe.clawdea.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawDEASettingsSelectedEffortTest {

    @Test
    fun `selected effort is partitioned per working directory`() {
        val settings = ClawDEASettings()
        val dirA = "/tmp/project-a"
        val dirB = "/tmp/project-b"

        settings.setSelectedEffort(dirA, "high")
        settings.setSelectedEffort(dirB, "low")

        assertEquals("high", settings.getSelectedEffort(dirA))
        assertEquals("low", settings.getSelectedEffort(dirB))
    }

    @Test
    fun `getSelectedEffort returns empty string when unset`() {
        val settings = ClawDEASettings()
        assertEquals("", settings.getSelectedEffort("/tmp/never-set"))
    }

    @Test
    fun `setSelectedEffort with blank removes the entry`() {
        val settings = ClawDEASettings()
        val dir = "/tmp/project-c"

        settings.setSelectedEffort(dir, "max")
        assertTrue(settings.state.selectedEfforts.containsKey(dir))

        settings.setSelectedEffort(dir, "")
        assertEquals("", settings.getSelectedEffort(dir))
        assertFalse(settings.state.selectedEfforts.containsKey(dir))
    }
}
