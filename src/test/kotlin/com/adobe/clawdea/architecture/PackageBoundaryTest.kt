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
package com.adobe.clawdea.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageBoundaryTest {
    private val packagesThatMustNotDependOnChat = setOf(
        "approval", "auth", "cli", "cost", "editreview", "gateway",
        "knowledge", "mcp", "provider", "session", "util", "vfs",
    )

    @Test
    fun `non UI packages do not import chat`() {
        val sourceRoot = Path.of("src/main/kotlin/com/adobe/clawdea")
        val offenders = mutableListOf<String>()

        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { file ->
                    val relative = sourceRoot.relativize(file)
                    if (relative.nameCount == 0 ||
                        relative.getName(0).toString() !in packagesThatMustNotDependOnChat
                    ) {
                        return@forEach
                    }
                    Files.readAllLines(file).forEachIndexed { index, line ->
                        if (line.trimStart().startsWith("import com.adobe.clawdea.chat")) {
                            offenders += "${relative}:${index + 1}: ${line.trim()}"
                        }
                    }
                }
        }

        assertTrue(
            "Non-UI packages must not import chat:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }
}
