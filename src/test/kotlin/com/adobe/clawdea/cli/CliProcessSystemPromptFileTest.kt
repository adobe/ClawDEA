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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression guard: the system prompt (MCP guidance + edit review + skill catalog
 * + primer) is easily >32KB, which exceeds Windows' CreateProcess 32,767-char
 * command-line limit and can approach ARG_MAX on Linux for large primers. We
 * must write it to a temp file and pass `--append-system-prompt-file`, not the
 * inline `--append-system-prompt <value>` form.
 */
class CliProcessSystemPromptFileTest {

    @Test
    fun `CliProcess passes append-system-prompt-file, not inline form`() {
        val source = stripComments(readSource())
        assertTrue(
            "CliProcess.kt must pass --append-system-prompt-file so the prompt is " +
                "read from a temp file (Windows CreateProcess limits command-line " +
                "length to 32767 chars).",
            source.contains("--append-system-prompt-file"),
        )
        assertFalse(
            "CliProcess.kt must not pass --append-system-prompt inline — the " +
                "assembled prompt commonly exceeds Windows' 32KB command-line " +
                "limit. Use --append-system-prompt-file instead.",
            containsInlineAppendSystemPrompt(source),
        )
    }

    @Test
    fun `CliProcess cleans up temp system prompt file on stop`() {
        val source = readSource()
        assertTrue(
            "CliProcess.stop() must delete the temp system prompt file to avoid " +
                "leaking files across sessions.",
            source.contains("systemPromptFile?.delete()"),
        )
    }

    /**
     * Detect the inline form `"--append-system-prompt"` (followed by comma) as
     * distinct from the file form `"--append-system-prompt-file"`. Using a
     * regex lookahead to rule out the `-file` suffix.
     */
    private fun containsInlineAppendSystemPrompt(source: String): Boolean {
        val regex = Regex(""""--append-system-prompt"(?!-file)""")
        return regex.containsMatchIn(source)
    }

    private fun stripComments(source: String): String {
        val withoutBlockComments = Regex("/\\*[\\s\\S]*?\\*/").replace(source, "")
        return withoutBlockComments
            .lineSequence()
            .joinToString("\n") { line ->
                val idx = line.indexOf("//")
                if (idx == -1) line else line.substring(0, idx)
            }
    }

    private fun readSource(): String {
        val path = Path.of(System.getProperty("user.dir"))
            .resolve("src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt")
        return Files.readString(path)
    }
}
