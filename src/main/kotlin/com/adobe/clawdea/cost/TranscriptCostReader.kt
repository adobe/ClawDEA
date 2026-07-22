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

import com.adobe.clawdea.util.ClaudeProjectDir
import java.io.File

/**
 * Reconstructs prior-turn cost from a Claude Code session transcript for resume
 * seeding. The transcript persists no dollar figure, so cost is computed from each
 * assistant message's token `usage` via [ModelPricing]. Path scheme mirrors SessionScanner.
 *
 * De-duplication: Claude Code rewrites the same assistant message to the `.jsonl`
 * multiple times as it streams (observed up to 4 byte-identical copies sharing one
 * `message.id`). Pricing every line over-counts by ~3x, so each `message.id` is
 * priced once.
 */
object TranscriptCostReader {

    /** Resume seed: total reconstructed cost and the model of the last priced turn (for the footer). */
    data class ResumeCost(val totalUsd: Double, val lastModel: String?)

    fun sessionTranscriptFile(projectBasePath: String, sessionId: String): File {
        val encodedPath = ClaudeProjectDir.encode(projectBasePath)
        return File(System.getProperty("user.home") + "/.claude/projects/" + encodedPath + "/$sessionId.jsonl")
    }

    /** Total reconstructed cost, de-duplicated by message id. */
    fun sumCost(file: File): Double = readResumeCost(file).totalUsd

    /** Total cost + last model seen, de-duplicated by message id. */
    fun readResumeCost(file: File): ResumeCost {
        if (!file.isFile) return ResumeCost(0.0, null)
        var total = 0.0
        var lastModel: String? = null
        val seen = HashSet<String>()
        var lineIndex = 0
        try {
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val priced = priceLine(line, lineIndex++, seen)
                    if (priced != null) {
                        total += priced.first
                        priced.second?.let { lastModel = it }
                    }
                }
            }
        } catch (_: Exception) {
            // Best-effort: seeding is non-critical.
        }
        return ResumeCost(total, lastModel)
    }

    /**
     * Price one transcript line, or null if it carries no usage. Assistant lines carry
     * `model` and a `usage` object (both nested inside `message`). Returns (cost, model).
     * A line whose `message.id` was already priced returns 0.0 cost (still reports its
     * model so the footer reflects the latest turn) — see the dedup note on the object.
     */
    internal fun priceLine(line: String, lineIndex: Int, seen: MutableSet<String>): Pair<Double, String?>? {
        val usageStart = line.indexOf("\"usage\"")
        if (usageStart == -1) return null
        val model = extractStringValue(line, "\"model\"") ?: return null
        // De-dup key: the message id (msg_...), unique per logical assistant turn.
        // Lines without one (e.g. test fixtures) fall back to a per-line key so each counts once.
        val messageStart = line.indexOf("\"message\"")
        val msgId = if (messageStart >= 0) extractStringValue(line.substring(messageStart), "\"id\"") else null
        val dedupKey = msgId ?: "__line_$lineIndex"
        if (!seen.add(dedupKey)) return 0.0 to model
        val usage = line.substring(usageStart)
        val input = extractIntValue(usage, "\"input_tokens\"")
        val output = extractIntValue(usage, "\"output_tokens\"")
        val cacheRead = extractIntValue(usage, "\"cache_read_input_tokens\"")
        val cacheCreate = extractIntValue(usage, "\"cache_creation_input_tokens\"")
        return ModelPricing.costFor(model, input, output, cacheRead, cacheCreate) to model
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
