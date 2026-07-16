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
package com.adobe.clawdea.provider.openai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpenAiCompatiblePrivacyGuardTest {
    @Test
    fun `generic provider contains no externally supplied private literals`() {
        val forbidden = System.getenv("CLAWDEA_PRIVATE_PROFILE_LITERALS")
            .orEmpty()
            .split('|')
            .filter(String::isNotBlank)
        if (forbidden.isEmpty()) return

        val roots = listOf("src/main/kotlin", "src/main/resources", "src/test/resources/openai-compatible", "README.md", "docs/user-guide.md")
        val violations = scanTextFiles(roots).flatMap { (path, text) ->
            forbidden.filter { text.contains(it, ignoreCase = true) }.map { "$path contains a forbidden literal" }
        }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    private fun scanTextFiles(roots: List<String>): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (root in roots) {
            val file = File(root)
            if (file.isFile) {
                // Single file root (README.md, docs/user-guide.md)
                result.add(file.path to file.readText())
            } else if (file.isDirectory) {
                // Directory root - walk recursively
                file.walk()
                    .filter { it.isFile }
                    .filter { isTextFile(it) }
                    .forEach { result.add(it.path to it.readText()) }
            }
        }
        return result
    }

    private fun isTextFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in setOf("kt", "kts", "java", "xml", "json", "properties", "yaml", "yml", "md", "txt", "html")
    }
}
