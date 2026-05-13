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
import org.junit.Test

class ResolveClaudeCliPathTest {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    @Test
    fun `returns configured path unchanged when non-empty and not claude`() {
        val result = resolveClaudeCliPath("/opt/custom/claude")
        // On Windows the helper normalizes .ps1 but leaves non-.ps1 paths alone.
        assertEquals("/opt/custom/claude", result)
    }

    @Test
    fun `normalizeWindowsShimPath is a no-op on non-Windows`() {
        if (isWindows) return
        assertEquals("/tmp/foo.ps1", normalizeWindowsShimPath("/tmp/foo.ps1"))
        assertEquals("claude", normalizeWindowsShimPath("claude"))
    }

    @Test
    fun `normalizeWindowsShimPath redirects ps1 to cmd sibling when present`() {
        if (!isWindows) return
        val dir = java.nio.file.Files.createTempDirectory("clawdea-shim").toFile()
        try {
            val ps1 = java.io.File(dir, "claude.ps1").apply { writeText("# ps1 shim") }
            val cmd = java.io.File(dir, "claude.cmd").apply { writeText("@echo off") }
            val result = normalizeWindowsShimPath(ps1.absolutePath)
            assertEquals(cmd.absolutePath, result)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `normalizeWindowsShimPath leaves ps1 alone when no cmd sibling exists`() {
        if (!isWindows) return
        val dir = java.nio.file.Files.createTempDirectory("clawdea-shim").toFile()
        try {
            val ps1 = java.io.File(dir, "claude.ps1").apply { writeText("# ps1 shim") }
            val result = normalizeWindowsShimPath(ps1.absolutePath)
            assertEquals(ps1.absolutePath, result)
        } finally {
            dir.deleteRecursively()
        }
    }
}
