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
package com.adobe.clawdea.profiling.mcp

import com.adobe.clawdea.mcp.McpToolRouter
import com.adobe.clawdea.profiling.analysis.AnalysisService
import com.google.gson.Gson

class McpProfilingTools(private val analysisService: AnalysisService) {

    private val gson = Gson()

    fun registerAll(router: McpToolRouter) {
        router.register(
            name = "profiling_start",
            description = "Start a profiling session.",
            properties = listOf(
                Triple("target", "string", "Run config name, test FQN (prefixed with 'test:'), or PID (prefixed with 'pid:')"),
                Triple("categories", "string", "Comma-separated: cpu,allocations,heap_leak. Default: cpu,allocations"),
            ),
            required = listOf("target"),
            handler = ::handleStart,
        )
        router.register(
            name = "profiling_stop",
            description = "Stop an active profiling session.",
            properties = listOf(Triple("session_id", "string", "The session ID returned by profiling_start")),
            required = listOf("session_id"),
            handler = ::handleStop,
        )
        router.register(
            name = "profiling_status",
            description = "Query the state of a profiling session.",
            properties = listOf(Triple("session_id", "string", "The session ID to query")),
            required = listOf("session_id"),
            handler = ::handleStatus,
        )
        router.register(
            name = "profiling_list",
            description = "List available recordings with metadata.",
            properties = emptyList(),
            required = emptyList(),
            handler = ::handleList,
        )
        router.register(
            name = "profiling_import",
            description = "Import a .jfr or .hprof file for analysis.",
            properties = listOf(
                Triple("path", "string", "Absolute path to .jfr or .hprof file"),
                Triple("note", "string", "Optional note about this recording"),
            ),
            required = listOf("path"),
            handler = ::handleImport,
        )
        router.register(
            name = "profiling_analyze_cpu",
            description = "Analyze CPU hotspots in a recording.",
            properties = listOf(
                Triple("recording_id", "string", "The recording to analyze"),
                Triple("top_n", "string", "Max results to return (default 50)"),
                Triple("thread_filter", "string", "Optional: only analyze samples from this thread name"),
            ),
            required = listOf("recording_id"),
            handler = ::handleAnalyzeCpu,
        )
        router.register(
            name = "profiling_analyze_allocations",
            description = "Analyze allocation hotspots in a recording.",
            properties = listOf(
                Triple("recording_id", "string", "The recording to analyze"),
                Triple("top_n", "string", "Max results to return (default 50)"),
                Triple("class_filter", "string", "Optional: only analyze allocations of this class"),
            ),
            required = listOf("recording_id"),
            handler = ::handleAnalyzeAllocations,
        )
        router.register(
            name = "profiling_analyze_leaks",
            description = "Analyze memory leaks in a heap dump (.hprof only).",
            properties = listOf(
                Triple("recording_id", "string", "The recording to analyze (must be from .hprof)"),
                Triple("top_n", "string", "Max results to return (default 50)"),
            ),
            required = listOf("recording_id"),
            handler = ::handleAnalyzeLeaks,
        )
    }

    private fun handleStart(args: Map<String, String>): McpToolRouter.ToolResult {
        return McpToolRouter.ToolResult("Profiling session start not yet wired to CaptureService.", isError = true)
    }

    private fun handleStop(args: Map<String, String>): McpToolRouter.ToolResult {
        return McpToolRouter.ToolResult("No active session.", isError = true)
    }

    private fun handleStatus(args: Map<String, String>): McpToolRouter.ToolResult {
        return McpToolRouter.ToolResult("No active session.", isError = true)
    }

    private fun handleList(args: Map<String, String>): McpToolRouter.ToolResult {
        val recordings = analysisService.listRecordings()
        val entries = recordings.map { (id, rec) ->
            mapOf(
                "id" to id,
                "source" to rec.source.name,
                "cpu_samples" to rec.cpuSamples.size,
                "allocations" to rec.allocations.size,
                "heap_objects" to rec.heap.size,
                "duration_ms" to rec.timeRange.durationMs,
            )
        }
        return McpToolRouter.ToolResult(gson.toJson(mapOf("recordings" to entries)))
    }

    private fun handleImport(args: Map<String, String>): McpToolRouter.ToolResult {
        return McpToolRouter.ToolResult("Import not yet wired.", isError = true)
    }

    private fun handleAnalyzeCpu(args: Map<String, String>): McpToolRouter.ToolResult {
        val recordingId = args["recording_id"] ?: return McpToolRouter.ToolResult("recording_id required", isError = true)
        val topN = args["top_n"]?.toIntOrNull() ?: 50
        val threadFilter = args["thread_filter"]
        return try {
            val result = analysisService.analyzeCpu(recordingId, topN, threadFilter)
            McpToolRouter.ToolResult(gson.toJson(result))
        } catch (e: Exception) {
            McpToolRouter.ToolResult("Analysis failed: ${e.message}", isError = true)
        }
    }

    private fun handleAnalyzeAllocations(args: Map<String, String>): McpToolRouter.ToolResult {
        val recordingId = args["recording_id"] ?: return McpToolRouter.ToolResult("recording_id required", isError = true)
        val topN = args["top_n"]?.toIntOrNull() ?: 50
        val classFilter = args["class_filter"]
        return try {
            val result = analysisService.analyzeAllocations(recordingId, topN, classFilter)
            McpToolRouter.ToolResult(gson.toJson(result))
        } catch (e: Exception) {
            McpToolRouter.ToolResult("Analysis failed: ${e.message}", isError = true)
        }
    }

    private fun handleAnalyzeLeaks(args: Map<String, String>): McpToolRouter.ToolResult {
        val recordingId = args["recording_id"] ?: return McpToolRouter.ToolResult("recording_id required", isError = true)
        val topN = args["top_n"]?.toIntOrNull() ?: 50
        return try {
            val result = analysisService.analyzeLeaks(recordingId, topN)
            McpToolRouter.ToolResult(gson.toJson(result))
        } catch (e: Exception) {
            McpToolRouter.ToolResult("Analysis failed: ${e.message}", isError = true)
        }
    }
}
