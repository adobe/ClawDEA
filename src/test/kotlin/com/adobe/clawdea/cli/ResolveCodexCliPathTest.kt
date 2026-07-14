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
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveCodexCliPathTest {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    @Test
    fun `returns configured path unchanged when non-empty and not codex`() {
        val result = resolveCodexCliPath("/opt/custom/codex")
        assertEquals("/opt/custom/codex", result)
    }

    @Test
    fun `falls back to bare codex when nothing configured and no install found`() {
        // On a machine without codex in the probed locations this returns the bare name;
        // when codex IS installed in a well-known dir it returns that absolute path. Either
        // way the result must end in the binary name so the OS/PATH can launch it.
        val result = resolveCodexCliPath("codex")
        assertEquals("codex", java.io.File(result).name)
    }

    @Test
    fun `findBinaryOnWindowsPath locates codex shim via injected exists`() {
        val found = findBinaryOnWindowsPath(
            binary = "codex",
            path = "C:\\tools;C:\\npm",
            pathExt = ".EXE;.CMD",
            exists = { it == "C:\\npm\\codex.CMD" },
        )
        assertEquals("C:\\npm\\codex.CMD", found)
    }

    @Test
    fun `findBinaryOnWindowsPath returns null when codex is not on PATH`() {
        val found = findBinaryOnWindowsPath(
            binary = "codex",
            path = "C:\\tools;C:\\npm",
            pathExt = ".EXE;.CMD",
            exists = { false },
        )
        assertNull(found)
    }
}
