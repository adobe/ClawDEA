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

/**
 * Windows PATH+PATHEXT resolution for the `claude` CLI. ProcessBuilder on Windows does NOT replicate
 * cmd.exe's PATH/PATHEXT lookup, so a bare "claude" fails with CreateProcess error=2 even when
 * claude.cmd is on PATH (the v1.8.0 Windows bug). These tests pin the pure lookup that replaces the
 * bare-name fallback. Pure: PATH/PATHEXT strings + an `exists` predicate are injected, no real FS.
 */
class FindClaudeOnWindowsPathTest {

    private val pathext = ".COM;.EXE;.BAT;.CMD"

    @Test fun `finds claude_cmd on PATH via PATHEXT`() {
        val path = """C:\nope;C:\Users\Me\AppData\Roaming\npm;C:\other"""
        val present = setOf("""C:\Users\Me\AppData\Roaming\npm\claude.CMD""")
        assertEquals(
            """C:\Users\Me\AppData\Roaming\npm\claude.CMD""",
            findClaudeOnWindowsPath(path, pathext) { it in present },
        )
    }

    @Test fun `returns null when claude is on no PATH entry`() {
        val path = """C:\nope;C:\also-nope"""
        assertNull(findClaudeOnWindowsPath(path, pathext) { false })
    }

    @Test fun `honors PATH order - first matching directory wins`() {
        val path = """C:\first;C:\second"""
        val present = setOf("""C:\first\claude.EXE""", """C:\second\claude.CMD""")
        assertEquals(
            """C:\first\claude.EXE""",
            findClaudeOnWindowsPath(path, pathext) { it in present },
        )
    }

    @Test fun `honors PATHEXT order within a directory`() {
        // .BAT comes before .CMD in PATHEXT, so a dir holding both resolves to .BAT.
        val path = """C:\tools"""
        val present = setOf("""C:\tools\claude.BAT""", """C:\tools\claude.CMD""")
        assertEquals(
            """C:\tools\claude.BAT""",
            findClaudeOnWindowsPath(path, pathext) { it in present },
        )
    }

    @Test fun `empty or blank PATH yields null`() {
        assertNull(findClaudeOnWindowsPath("", pathext) { true })
        assertNull(findClaudeOnWindowsPath("   ;  ", pathext) { false })
    }

    @Test fun `falls back to a default PATHEXT when none is provided`() {
        // A blank PATHEXT must not disable the search — Windows always has .CMD semantics.
        val path = """C:\tools"""
        val present = setOf("""C:\tools\claude.CMD""")
        assertEquals(
            """C:\tools\claude.CMD""",
            findClaudeOnWindowsPath(path, "") { it in present },
        )
    }
}
