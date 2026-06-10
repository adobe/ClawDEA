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
package com.adobe.clawdea.cost

import java.io.File

/**
 * Sums prior-turn cost from a Claude Code session transcript for resume seeding.
 * Per-turn semantics. Path scheme mirrors SessionScanner.
 */
object TranscriptCostReader {

    fun sessionTranscriptFile(projectBasePath: String, sessionId: String): File {
        val encodedPath = "-" + projectBasePath.trimStart('/').replace("/", "-")
        return File(System.getProperty("user.home") + "/.claude/projects/" + encodedPath + "/$sessionId.jsonl")
    }

    fun sumCost(file: File): Double {
        if (!file.isFile) return 0.0
        var total = 0.0
        try {
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("\"total_cost_usd\"")) total += extractCost(line)
                }
            }
        } catch (_: Exception) {
            // Best-effort: seeding is non-critical.
        }
        return total
    }

    internal fun extractCost(line: String): Double {
        val key = "\"total_cost_usd\""
        val i = line.indexOf(key)
        if (i == -1) return 0.0
        val colon = line.indexOf(':', i + key.length)
        if (colon == -1) return 0.0
        val after = line.substring(colon + 1).trimStart()
        val num = after.takeWhile { it.isDigit() || it == '.' || it == '-' || it == 'e' || it == 'E' }
        return num.toDoubleOrNull() ?: 0.0
    }
}
