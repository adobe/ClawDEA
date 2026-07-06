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
 * librarian re-ride multiplier — the accurate estimate. Manual line parsing and message.id
 * dedup mirror [TranscriptCostReader] — Claude Code rewrites streamed lines, so each
 * message.id is counted once.
 */
object TranscriptSavingsReader {

    data class Reconstruction(
        val band: SavingsBand,
        val turns: Int,
        /** Per-lever bands summed across every turn in the transcript (for global seeding on resume). */
        val leverBands: Map<LeverId, SavingsBand> = emptyMap(),
    )

    fun reconstructFile(file: File): Reconstruction {
        if (!file.isFile) return Reconstruction(SavingsBand.ZERO, 0)
        return try {
            file.bufferedReader().useLines { reconstruct(it.toList()) }
        } catch (_: Exception) {
            Reconstruction(SavingsBand.ZERO, 0)
        }
    }

    /**
     * A top-level turn ends on a `result` line. Streamed duplicate result lines sharing one
     * `message.id` count once; result lines without a `message.id` (fixtures, system lines)
     * each count once (per-line fallback, mirroring [TranscriptCostReader]).
     */
    fun countTopLevelTurns(lines: List<String>): Int {
        val seen = HashSet<String>()
        var count = 0
        for (line in lines) {
            if (!line.contains("\"type\":\"result\"")) continue
            val id = messageId(line)
            if (id != null && !seen.add(id)) continue // duplicate streamed result → skip
            count++
        }
        return count
    }

    /** A line belongs to a subagent when it carries a non-null parent tool-use id (snake or camel). */
    fun isSubagentLine(line: String): Boolean {
        for (key in PARENT_TOOL_USE_KEYS) {
            val i = line.indexOf(key)
            if (i == -1) continue
            val after = line.substring(i + key.length)
            val colon = after.indexOf(':')
            if (colon == -1) continue
            val rest = after.substring(colon + 1).trimStart()
            if (!rest.startsWith("null")) return true
        }
        return false
    }

    private val PARENT_TOOL_USE_KEYS = listOf("\"parent_tool_use_id\"", "\"parentToolUseId\"")

    /**
     * Group lines into top-level turns (split on `result` lines), build a [TurnObservation] per
     * turn with the real remaining-turns count, and aggregate. Subagent lines (parentToolUseId)
     * within a turn become that turn's [SubagentObservation]s.
     */
    fun reconstruct(lines: List<String>): Reconstruction {
        val totalTurns = countTopLevelTurns(lines)
        if (totalTurns == 0) return Reconstruction(SavingsBand.ZERO, 0)

        val seenResultIds = HashSet<String>()
        val seenSubagentIds = HashSet<String>()
        var band = SavingsBand.ZERO
        var leverBands = emptyMap<LeverId, SavingsBand>()
        var turnIndex = 0
        val current = mutableListOf<String>()
        for (line in lines) {
            val isResult = line.contains("\"type\":\"result\"")
            if (isResult) {
                val id = messageId(line)
                if (id != null && !seenResultIds.add(id)) continue // duplicate streamed result → ignore
                val remaining = totalTurns - turnIndex - 1
                val obs = buildTurn(current, remaining, seenSubagentIds)
                val turnBand = SavingsEstimator.aggregate(obs)
                band += turnBand
                for (c in SavingsEstimator.components(obs)) {
                    if (c.measured) continue
                    val prev = leverBands[c.leverId] ?: SavingsBand.ZERO
                    leverBands = leverBands + (c.leverId to (prev + c.band))
                }
                current.clear()
                turnIndex++
            } else {
                current.add(line)
            }
        }
        return Reconstruction(band, totalTurns, leverBands)
    }

    private fun buildTurn(
        turnLines: List<String>,
        remainingTurns: Int,
        seenSubagentIds: HashSet<String>,
    ): TurnObservation {
        var model = "claude-opus-4-8"
        val subagents = mutableListOf<SubagentObservation>()
        val indexTools = mutableListOf<IndexToolObservation>()
        val pendingIndex = mutableMapOf<String, String>()
        for (line in turnLines) {
            val lineModel = extractString(line, "\"model\"")
            if (isSubagentLine(line)) {
                val id = messageId(line)
                if (id != null && !seenSubagentIds.add(id)) continue // duplicate streamed subagent line → skip
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
                            agentType = extractString(line, "\"subagent_type\"") ?: "subagent",
                            costUsd = cost,
                            summaryTokens = 0,
                            filesReadTokens = input,
                            inputTokens = input,
                        ),
                    )
                }
            } else {
                extractToolUses(line).forEach { (id, name) ->
                    if (TurnObservationBuilder.isIndexTool(name)) pendingIndex[id] = name
                }
                extractToolResult(line)?.let { (toolUseId, content) ->
                    pendingIndex.remove(toolUseId)?.let { name ->
                        val tokens = TurnObservationBuilder.estimateTokens(content)
                        val hits = (tokens / 100).coerceAtLeast(1)
                        indexTools.add(IndexToolObservation(name, hits, tokens))
                    }
                }
                if (lineModel != null && !isSubagentLine(line)) {
                    model = lineModel
                }
            }
        }
        return TurnObservation(
            model = model,
            remainingTurns = remainingTurns,
            subagents = subagents,
            indexTools = indexTools,
        )
    }

    /** tool_use id → name pairs from an assistant/user line's content array. */
    private fun extractToolUses(line: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var searchFrom = 0
        while (true) {
            val typeIdx = line.indexOf("\"type\":\"tool_use\"", searchFrom)
            if (typeIdx == -1) break
            val blockStart = line.lastIndexOf('{', typeIdx)
            val blockEnd = line.indexOf('}', typeIdx)
            if (blockStart == -1 || blockEnd == -1) break
            val block = line.substring(blockStart, blockEnd + 1)
            val id = extractString(block, "\"id\"") ?: ""
            val name = extractString(block, "\"name\"") ?: ""
            if (id.isNotBlank() && name.isNotBlank()) out.add(id to name)
            searchFrom = blockEnd + 1
        }
        return out
    }

    /** tool_use_id + text content when this line is a tool_result block. */
    private fun extractToolResult(line: String): Pair<String, String>? {
        if (!line.contains("\"type\":\"tool_result\"")) return null
        val toolUseId = extractString(line, "\"tool_use_id\"") ?: return null
        val content = extractToolResultContent(line)
        return toolUseId to content
    }

    private fun extractToolResultContent(line: String): String {
        extractString(line, "\"content\"")?.let { return it }
        val parts = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val typeIdx = line.indexOf("\"type\":\"text\"", searchFrom)
            if (typeIdx == -1) break
            val blockStart = line.lastIndexOf('{', typeIdx)
            val blockEnd = line.indexOf('}', typeIdx)
            if (blockStart == -1 || blockEnd == -1) break
            extractString(line.substring(blockStart, blockEnd + 1), "\"text\"")?.let { parts.add(it) }
            searchFrom = blockEnd + 1
        }
        return parts.joinToString("\n")
    }

    /** message.id for dedup of streamed duplicate lines, or null if absent. */
    private fun messageId(line: String): String? {
        val m = line.indexOf("\"message\"")
        if (m == -1) return null
        return extractString(line.substring(m), "\"id\"")
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
