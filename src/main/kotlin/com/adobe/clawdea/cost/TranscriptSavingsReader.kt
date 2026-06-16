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
 * Reconstructs a session's savings band from its `.jsonl` transcript for resume seeding.
 * Unlike the live path (which cannot see the future and uses a single-turn re-ride floor),
 * the full session is on disk here, so each turn's REAL remaining-turns count drives the
 * librarian re-ride multiplier — the accurate estimate. Manual line parsing + dedup mirror
 * [TranscriptCostReader].
 */
object TranscriptSavingsReader {

    data class Reconstruction(val band: SavingsBand, val turns: Int)

    fun reconstructFile(file: File): Reconstruction {
        if (!file.isFile) return Reconstruction(SavingsBand.ZERO, 0)
        return try {
            file.bufferedReader().useLines { reconstruct(it.toList()) }
        } catch (_: Exception) {
            Reconstruction(SavingsBand.ZERO, 0)
        }
    }

    /** A top-level turn ends on a `result` line. */
    fun countTopLevelTurns(lines: List<String>): Int =
        lines.count { it.contains("\"type\":\"result\"") }

    /** A line belongs to a subagent when it carries a non-null `parentToolUseId`. */
    fun isSubagentLine(line: String): Boolean {
        val i = line.indexOf("\"parentToolUseId\"")
        if (i == -1) return false
        val after = line.substring(i + "\"parentToolUseId\"".length)
        val colon = after.indexOf(':')
        if (colon == -1) return false
        val rest = after.substring(colon + 1).trimStart()
        return !rest.startsWith("null")
    }

    /**
     * Group lines into top-level turns (split on `result` lines), build a [TurnObservation] per
     * turn with the real remaining-turns count, and aggregate. Subagent lines (parentToolUseId)
     * within a turn become that turn's [SubagentObservation]s.
     */
    fun reconstruct(lines: List<String>): Reconstruction {
        val totalTurns = countTopLevelTurns(lines)
        if (totalTurns == 0) return Reconstruction(SavingsBand.ZERO, 0)

        var band = SavingsBand.ZERO
        var turnIndex = 0
        val current = mutableListOf<String>()
        for (line in lines) {
            current.add(line)
            if (line.contains("\"type\":\"result\"")) {
                val remaining = totalTurns - turnIndex - 1
                band += SavingsEstimator.aggregate(buildTurn(current, remaining))
                current.clear()
                turnIndex++
            }
        }
        return Reconstruction(band, totalTurns)
    }

    private fun buildTurn(turnLines: List<String>, remainingTurns: Int): TurnObservation {
        var model = "claude-opus-4-8"
        val subagents = mutableListOf<SubagentObservation>()
        for (line in turnLines) {
            val lineModel = extractString(line, "\"model\"")
            if (isSubagentLine(line)) {
                val input = extractUsageInt(line, "\"input_tokens\"")
                if (input > 0) {
                    val cost = ModelPricing.costFor(
                        lineModel ?: model,
                        input,
                        extractUsageInt(line, "\"output_tokens\""),
                        extractUsageInt(line, "\"cache_read_input_tokens\""),
                        extractUsageInt(line, "\"cache_creation_input_tokens\""),
                    )
                    subagents.add(
                        SubagentObservation(
                            agentType = "subagent",
                            costUsd = cost,
                            summaryTokens = 0,
                            filesReadTokens = input,
                            inputTokens = input,
                        ),
                    )
                }
            } else if (lineModel != null) {
                model = lineModel
            }
        }
        return TurnObservation(model = model, remainingTurns = remainingTurns, subagents = subagents)
    }

    private fun extractString(s: String, key: String): String? {
        val i = s.indexOf(key); if (i == -1) return null
        val colon = s.indexOf(':', i + key.length); if (colon == -1) return null
        val q1 = s.indexOf('"', colon + 1); if (q1 == -1) return null
        val q2 = s.indexOf('"', q1 + 1); if (q2 == -1) return null
        return s.substring(q1 + 1, q2)
    }

    private fun extractUsageInt(line: String, key: String): Int {
        val usageStart = line.indexOf("\"usage\""); if (usageStart == -1) return 0
        val s = line.substring(usageStart)
        val i = s.indexOf(key); if (i == -1) return 0
        val colon = s.indexOf(':', i + key.length); if (colon == -1) return 0
        val after = s.substring(colon + 1).trimStart()
        return after.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
}
