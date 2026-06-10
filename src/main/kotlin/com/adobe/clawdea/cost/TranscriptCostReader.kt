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
                lines.forEach { line -> total += priceLine(line) }
            }
        } catch (_: Exception) {
            // Best-effort: seeding is non-critical.
        }
        return total
    }

    /**
     * Price one transcript line. Assistant lines carry `model` and a `usage` object
     * (both nested inside `message`); everything else contributes 0. The transcript
     * has no persisted dollar field, so we compute from tokens via [ModelPricing].
     */
    internal fun priceLine(line: String): Double {
        val usageStart = line.indexOf("\"usage\"")
        if (usageStart == -1) return 0.0
        val model = extractStringValue(line, "\"model\"") ?: return 0.0
        val usage = line.substring(usageStart)
        val input = extractIntValue(usage, "\"input_tokens\"")
        val output = extractIntValue(usage, "\"output_tokens\"")
        val cacheRead = extractIntValue(usage, "\"cache_read_input_tokens\"")
        val cacheCreate = extractIntValue(usage, "\"cache_creation_input_tokens\"")
        return ModelPricing.costFor(model, input, output, cacheRead, cacheCreate)
    }

    private fun extractStringValue(s: String, key: String): String? {
        val i = s.indexOf(key)
        if (i == -1) return null
        val colon = s.indexOf(':', i + key.length)
        if (colon == -1) return null
        val q1 = s.indexOf('"', colon + 1)
        if (q1 == -1) return null
        val q2 = s.indexOf('"', q1 + 1)
        if (q2 == -1) return null
        return s.substring(q1 + 1, q2)
    }

    private fun extractIntValue(s: String, key: String): Int {
        val i = s.indexOf(key)
        if (i == -1) return 0
        val colon = s.indexOf(':', i + key.length)
        if (colon == -1) return 0
        val after = s.substring(colon + 1).trimStart()
        val num = after.takeWhile { it.isDigit() }
        return num.toIntOrNull() ?: 0
    }
}
