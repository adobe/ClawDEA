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
package com.adobe.clawdea.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliProcessPermissionArgsTest {

    @Test
    fun `confirm-all emits no permission mode flag`() {
        assertEquals(emptyList<String>(), CliProcess.buildPermissionArgs("confirm-all"))
    }

    @Test
    fun `confirm-all injects ask rules for read-only Bash commands`() {
        val json = CliProcess.buildPermissionSettingsJson("confirm-all")

        assertNotNull(json)
        assertTrue(json!!.contains(""""ask""""))
        assertTrue(json.contains("Bash(ls)"))
        assertTrue(json.contains("Bash(ls *)"))
        assertTrue(json.contains("Bash(pwd)"))
        assertTrue(json.contains("Bash(pwd *)"))
        assertTrue(json.contains("Bash(cat)"))
        assertTrue(json.contains("Bash(cat *)"))
    }

    @Test
    fun `allow-safe emits native --permission-mode auto`() {
        assertEquals(listOf("--permission-mode", "auto"), CliProcess.buildPermissionArgs("allow-safe"))
        assertNull(CliProcess.buildPermissionSettingsJson("allow-safe"))
    }

    @Test
    fun `allow-all emits no flag - silent-approve is handled by the prompt tool`() {
        assertEquals(emptyList<String>(), CliProcess.buildPermissionArgs("allow-all"))
        assertNull(CliProcess.buildPermissionSettingsJson("allow-all"))
    }

    @Test
    fun `project and local Claude settings are not loaded so project allowlists cannot preempt ClawDEA prompts`() {
        assertEquals(listOf("--setting-sources", "user"), CliProcess.buildSettingSourceArgs())
    }

    @Test
    fun `unknown value falls back to confirm-all behavior`() {
        assertEquals(emptyList<String>(), CliProcess.buildPermissionArgs("garbage"))
        assertNotNull(CliProcess.buildPermissionSettingsJson("garbage"))
    }

    @Test
    fun `empty and blank map to confirm-all`() {
        assertEquals(emptyList<String>(), CliProcess.buildPermissionArgs(""))
        assertEquals(emptyList<String>(), CliProcess.buildPermissionArgs("   "))
        assertNotNull(CliProcess.buildPermissionSettingsJson(""))
        assertNotNull(CliProcess.buildPermissionSettingsJson("   "))
    }

    @Test
    fun `no input ever emits --dangerously-skip-permissions`() {
        val inputs = listOf(
            "confirm-all", "allow-safe", "allow-all",
            "", "   ", "garbage",
            "dangerous", "skip-permissions", "--dangerously-skip-permissions",
            "ALLOW-ALL", "Allow-Safe",
        )
        for (input in inputs) {
            val args = CliProcess.buildPermissionArgs(input)
            assertFalse(
                "buildPermissionArgs($input) must never emit --dangerously-skip-permissions but got: $args",
                args.contains("--dangerously-skip-permissions"),
            )
        }
    }
}
