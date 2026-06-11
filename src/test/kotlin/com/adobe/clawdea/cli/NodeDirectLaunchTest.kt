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
 * Bypassing cmd.exe on Windows by launching `node cli.js …` directly.
 *
 * A `claude.cmd` shim is a batch file, so Java's ProcessBuilder routes its invocation through
 * cmd.exe, whose command line is capped at 8191 chars. With the wiki subagents injected, the
 * inline `--agents` JSON pushes the line past that cap and the CLI dies with
 * "The command line is too long." The native `node.exe` launch path (CreateProcess, 32767-char
 * limit) clears it.
 *
 * [nodeDirectLaunchPrefix] parses the cmd-shim's own format — the same `"%~dp0…\target.js" %*`
 * shape npm's `read-cmd-shim` matches — to recover `[node, <abs cli.js>]`. Pure: shim text +
 * directory + a `nodeExeExists` predicate are injected, no real FS.
 */
class NodeDirectLaunchTest {

    // Modern cmd-shim format (what `npm i -g @anthropic-ai/claude-code` actually writes).
    private val modernShim = """
        @ECHO off
        GOTO start
        :find_dp0
        SET dp0=%~dp0
        EXIT /b
        :start
        SETLOCAL
        CALL :find_dp0

        IF EXIST "%dp0%\node.exe" (
          SET "_prog=%dp0%\node.exe"
        ) ELSE (
          SET "_prog=node"
          SET PATHEXT=%PATHEXT:;.JS;=;%
        )

        endLocal & goto #_undefined_# 2>NUL || title %COMSPEC% & "%_prog%"  "%dp0%\node_modules\@anthropic-ai\claude-code\cli.js" %*
    """.trimIndent()

    // Older corepack-style shim (single IF EXIST, node on the exec line).
    private val legacyShim = """
        @SETLOCAL
        @IF EXIST "%~dp0\node.exe" (
          "%~dp0\node.exe"  "%~dp0\node_modules\@anthropic-ai\claude-code\cli.js" %*
        ) ELSE (
          @SET PATHEXT=%PATHEXT:;.JS;=;%
          node  "%~dp0\node_modules\@anthropic-ai\claude-code\cli.js" %*
        )
    """.trimIndent()

    private val dir = """C:\Users\Me\AppData\Roaming\npm"""
    private val cliJs = """C:\Users\Me\AppData\Roaming\npm\node_modules\@anthropic-ai\claude-code\cli.js"""

    @Test fun `modern shim with bundled node_exe resolves to that node and cli_js`() {
        assertEquals(
            listOf("""$dir\node.exe""", cliJs),
            nodeDirectLaunchPrefix(dir, modernShim) { true },
        )
    }

    @Test fun `modern shim without bundled node_exe falls back to bare node`() {
        assertEquals(
            listOf("node", cliJs),
            nodeDirectLaunchPrefix(dir, modernShim) { false },
        )
    }

    @Test fun `legacy corepack-style shim is parsed too`() {
        assertEquals(
            listOf("""$dir\node.exe""", cliJs),
            nodeDirectLaunchPrefix(dir, legacyShim) { true },
        )
    }

    @Test fun `non-shim content yields null (graceful fallback to the cmd)`() {
        assertNull(nodeDirectLaunchPrefix(dir, "echo not a shim\r\n") { true })
        assertNull(nodeDirectLaunchPrefix(dir, "") { true })
    }

    @Test fun `a shim whose target is not a _js_ file yields null`() {
        // We only redirect to node for JS targets; a non-JS exec target is left to cmd.exe.
        val exeShim = """"%dp0%\something.exe"  "%dp0%\payload.dat" %*"""
        assertNull(nodeDirectLaunchPrefix(dir, exeShim) { true })
    }

    @Test fun `full command rewrite swaps the cmd for node + cli_js, preserving args`() {
        val command = listOf("""$dir\claude.cmd""", "-p", "--agents", "{huge json}", "--model", "x")
        val rewritten = rewriteWindowsCmdToNodeLaunch(
            command,
            isWindows = true,
            readShim = { modernShim },
            nodeExeExists = { true },
        )
        assertEquals(
            listOf("""$dir\node.exe""", cliJs, "-p", "--agents", "{huge json}", "--model", "x"),
            rewritten,
        )
    }

    @Test fun `rewrite is a no-op off Windows`() {
        val command = listOf("""/usr/local/bin/claude""", "-p")
        assertEquals(
            command,
            rewriteWindowsCmdToNodeLaunch(command, isWindows = false, readShim = { modernShim }, nodeExeExists = { true }),
        )
    }

    @Test fun `rewrite is a no-op when argv0 is not a cmd or bat shim`() {
        val command = listOf("""C:\tools\claude.exe""", "-p")
        assertEquals(
            command,
            rewriteWindowsCmdToNodeLaunch(command, isWindows = true, readShim = { modernShim }, nodeExeExists = { true }),
        )
    }

    @Test fun `rewrite falls back to the original command when the shim cannot be parsed`() {
        val command = listOf("""C:\tools\claude.cmd""", "-p")
        assertEquals(
            command,
            rewriteWindowsCmdToNodeLaunch(command, isWindows = true, readShim = { "not a shim" }, nodeExeExists = { true }),
        )
    }

    @Test fun `rewrite falls back to the original command when reading the shim throws`() {
        val command = listOf("""C:\tools\claude.cmd""", "-p")
        assertEquals(
            command,
            rewriteWindowsCmdToNodeLaunch(
                command,
                isWindows = true,
                readShim = { throw java.io.IOException("boom") },
                nodeExeExists = { true },
            ),
        )
    }
}
