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
import com.adobe.clawdea.profiling.capture.CaptureRequest
import com.adobe.clawdea.profiling.capture.CaptureTarget
import com.adobe.clawdea.profiling.capture.Category
import com.adobe.clawdea.profiling.capture.SessionState
import com.adobe.clawdea.profiling.capture.jfr.JfrBackend
import com.adobe.clawdea.profiling.`import`.JfrImporter
import com.adobe.clawdea.profiling.settings.ProfilingSettings
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.google.gson.Gson
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class McpProfilingTools(
    private val project: Project,
    private val analysisService: AnalysisService,
) {

    private val gson = Gson()
    private val jfrBackend = JfrBackend(project)
    private val activeSessions = mutableMapOf<String, CompletableFuture<String?>>()

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
        val targetArg = args["target"] ?: return McpToolRouter.ToolResult("target required", isError = true)
        val categoriesArg = args["categories"] ?: "cpu,allocations"
        val categories = categoriesArg.split(",").mapNotNull { cat ->
            when (cat.trim().lowercase()) {
                "cpu" -> Category.CPU
                "allocations" -> Category.ALLOCATIONS
                "heap_leak" -> Category.HEAP_LEAK
                else -> null
            }
        }.toSet().ifEmpty { setOf(Category.CPU, Category.ALLOCATIONS) }

        val target = when {
            targetArg.startsWith("test:") -> CaptureTarget.TestMethod(targetArg.removePrefix("test:"))
            targetArg.startsWith("pid:") -> CaptureTarget.AttachPid(targetArg.removePrefix("pid:").toLongOrNull()
                ?: return McpToolRouter.ToolResult("Invalid PID: ${targetArg.removePrefix("pid:")}", isError = true))
            else -> CaptureTarget.RunConfig(targetArg)
        }

        val request = CaptureRequest(target = target, categories = categories)
        val settings = ProfilingSettings.sessionSettings()
        val sessionId = jfrBackend.start(request, settings)
        val jvmArgs = jfrBackend.buildJvmArgs(sessionId)

        return when (target) {
            is CaptureTarget.TestMethod -> launchTest(sessionId, target.fqn, jvmArgs)
            is CaptureTarget.RunConfig -> launchRunConfig(sessionId, target.configName, jvmArgs)
            is CaptureTarget.AttachPid -> McpToolRouter.ToolResult(
                "PID attach not yet supported. Use test: or run config targets.", isError = true)
            is CaptureTarget.ImportFile -> McpToolRouter.ToolResult(
                "Use profiling_import for file imports.", isError = true)
        }
    }

    private fun launchTest(sessionId: String, fqn: String, jvmArgs: List<String>): McpToolRouter.ToolResult {
        val configType = ConfigurationTypeUtil.findConfigurationType("JUnit")
            ?: return McpToolRouter.ToolResult("JUnit plugin not available.", isError = true)

        val runManager = RunManager.getInstance(project)
        val factory = configType.configurationFactories.first()
        val runSettings = runManager.createConfiguration("ClawDEA Profile: $fqn", factory)
        val config = runSettings.configuration

        configureTestTarget(config, fqn)
        setVmParameters(config, jvmArgs.joinToString(" "))

        val future = CompletableFuture<String?>()
        activeSessions[sessionId] = future

        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(runSettings, DefaultRunExecutor.getRunExecutorInstance())
        }

        attachProcessListener(sessionId, future, "ClawDEA Profile: $fqn")

        return McpToolRouter.ToolResult(gson.toJson(mapOf(
            "session_id" to sessionId,
            "state" to "RUNNING",
            "target" to fqn,
            "message" to "Profiling session started. The test is running with JFR. Call profiling_status to check progress, or wait for it to complete then call profiling_analyze_cpu/profiling_analyze_allocations with recording_id='$sessionId'.",
        )))
    }

    private fun launchRunConfig(sessionId: String, configName: String, jvmArgs: List<String>): McpToolRouter.ToolResult {
        val runManager = RunManager.getInstance(project)
        val runSettings = runManager.allSettings.find { it.name == configName }
            ?: return McpToolRouter.ToolResult(
                "Run configuration '$configName' not found. Available: ${runManager.allSettings.map { it.name }.joinToString(", ")}",
                isError = true)

        val config = runSettings.configuration
        val existingVm = getVmParameters(config)
        setVmParameters(config, "$existingVm ${jvmArgs.joinToString(" ")}".trim())

        val future = CompletableFuture<String?>()
        activeSessions[sessionId] = future

        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(runSettings, DefaultRunExecutor.getRunExecutorInstance())
        }

        attachProcessListener(sessionId, future, configName) {
            setVmParameters(config, existingVm)
        }

        return McpToolRouter.ToolResult(gson.toJson(mapOf(
            "session_id" to sessionId,
            "state" to "RUNNING",
            "target" to configName,
            "message" to "Profiling session started. Call profiling_status to check progress, or wait for completion then call profiling_analyze_cpu/profiling_analyze_allocations with recording_id='$sessionId'.",
        )))
    }

    private fun attachProcessListener(
        sessionId: String,
        future: CompletableFuture<String?>,
        configName: String,
        onTerminated: (() -> Unit)? = null,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            var handler: com.intellij.execution.process.ProcessHandler? = null
            repeat(40) {
                if (handler != null) return@repeat
                Thread.sleep(250)
                val descriptors = ExecutionManager.getInstance(project).getRunningDescriptors { it.name == configName }
                handler = descriptors.firstOrNull()?.processHandler
            }
            if (handler == null) {
                future.complete(null)
                return@executeOnPooledThread
            }
            jfrBackend.markRunning(sessionId)
            handler!!.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    onTerminated?.invoke()
                    val recording = jfrBackend.stop(sessionId)
                    if (recording != null) {
                        analysisService.register(sessionId, recording)
                        future.complete(sessionId)
                    } else {
                        future.complete(null)
                    }
                }
            })
            if (handler!!.isProcessTerminated) {
                onTerminated?.invoke()
                val recording = jfrBackend.stop(sessionId)
                if (recording != null) {
                    analysisService.register(sessionId, recording)
                    future.complete(sessionId)
                } else {
                    future.complete(null)
                }
            }
        }
    }

    private fun handleStop(args: Map<String, String>): McpToolRouter.ToolResult {
        val sessionId = args["session_id"] ?: return McpToolRouter.ToolResult("session_id required", isError = true)
        val state = jfrBackend.getState(sessionId)
            ?: return McpToolRouter.ToolResult("Session '$sessionId' not found.", isError = true)
        if (state == SessionState.DONE) {
            return McpToolRouter.ToolResult(gson.toJson(mapOf("session_id" to sessionId, "state" to "DONE")))
        }
        val recording = jfrBackend.stop(sessionId)
        if (recording != null) {
            analysisService.register(sessionId, recording)
        }
        activeSessions.remove(sessionId)
        return McpToolRouter.ToolResult(gson.toJson(mapOf(
            "session_id" to sessionId,
            "state" to "DONE",
            "has_recording" to (recording != null),
        )))
    }

    private fun handleStatus(args: Map<String, String>): McpToolRouter.ToolResult {
        val sessionId = args["session_id"] ?: return McpToolRouter.ToolResult("session_id required", isError = true)
        val state = jfrBackend.getState(sessionId)
            ?: return McpToolRouter.ToolResult("Session '$sessionId' not found.", isError = true)

        val future = activeSessions[sessionId]
        val hasRecording = future?.isDone == true && future.get() != null

        return McpToolRouter.ToolResult(gson.toJson(mapOf(
            "session_id" to sessionId,
            "state" to state.name,
            "has_recording" to hasRecording,
        )))
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
        val pathStr = args["path"] ?: return McpToolRouter.ToolResult("path required", isError = true)
        val path = Path.of(pathStr)
        if (!java.nio.file.Files.exists(path)) {
            return McpToolRouter.ToolResult("File not found: $pathStr", isError = true)
        }
        val ext = path.fileName.toString().substringAfterLast('.').lowercase()
        if (ext !in setOf("jfr", "hprof")) {
            return McpToolRouter.ToolResult("Unsupported file type: .$ext (expected .jfr or .hprof)", isError = true)
        }

        return try {
            val recording = when (ext) {
                "jfr" -> JfrImporter.import(path)
                else -> return McpToolRouter.ToolResult("hprof import not yet supported.", isError = true)
            }
            val id = path.fileName.toString().substringBeforeLast('.') + "-" +
                System.currentTimeMillis().toString().takeLast(6)
            analysisService.register(id, recording)
            McpToolRouter.ToolResult(gson.toJson(mapOf(
                "recording_id" to id,
                "source" to recording.source.name,
                "cpu_samples" to recording.cpuSamples.size,
                "allocations" to recording.allocations.size,
                "duration_ms" to recording.timeRange.durationMs,
            )))
        } catch (e: Exception) {
            McpToolRouter.ToolResult("Import failed: ${e.message}", isError = true)
        }
    }

    private fun handleAnalyzeCpu(args: Map<String, String>): McpToolRouter.ToolResult {
        val recordingId = args["recording_id"] ?: return McpToolRouter.ToolResult("recording_id required", isError = true)
        val topN = args["top_n"]?.toIntOrNull() ?: ProfilingSettings.topN()
        val threadFilter = args["thread_filter"]

        val future = activeSessions[recordingId]
        if (future != null && !future.isDone) {
            try { future.get(60, TimeUnit.SECONDS) } catch (_: Exception) {}
        }

        return try {
            val result = analysisService.analyzeCpu(recordingId, topN, threadFilter)
            McpToolRouter.ToolResult(gson.toJson(result))
        } catch (e: Exception) {
            McpToolRouter.ToolResult("Analysis failed: ${e.message}", isError = true)
        }
    }

    private fun handleAnalyzeAllocations(args: Map<String, String>): McpToolRouter.ToolResult {
        val recordingId = args["recording_id"] ?: return McpToolRouter.ToolResult("recording_id required", isError = true)
        val topN = args["top_n"]?.toIntOrNull() ?: ProfilingSettings.topN()
        val classFilter = args["class_filter"]

        val future = activeSessions[recordingId]
        if (future != null && !future.isDone) {
            try { future.get(60, TimeUnit.SECONDS) } catch (_: Exception) {}
        }

        return try {
            val result = analysisService.analyzeAllocations(recordingId, topN, classFilter)
            McpToolRouter.ToolResult(gson.toJson(result))
        } catch (e: Exception) {
            McpToolRouter.ToolResult("Analysis failed: ${e.message}", isError = true)
        }
    }

    private fun handleAnalyzeLeaks(args: Map<String, String>): McpToolRouter.ToolResult {
        val recordingId = args["recording_id"] ?: return McpToolRouter.ToolResult("recording_id required", isError = true)
        val topN = args["top_n"]?.toIntOrNull() ?: ProfilingSettings.topN()
        return try {
            val result = analysisService.analyzeLeaks(recordingId, topN)
            McpToolRouter.ToolResult(gson.toJson(result))
        } catch (e: Exception) {
            McpToolRouter.ToolResult("Analysis failed: ${e.message}", isError = true)
        }
    }

    private fun configureTestTarget(config: com.intellij.execution.configurations.RunConfiguration, fqn: String) {
        val (className, methodName) = if ('#' in fqn) {
            fqn.substringBeforeLast('#') to fqn.substringAfterLast('#')
        } else {
            fqn to null
        }

        val module = com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.module.Module?, Throwable> {
            val scope = GlobalSearchScope.projectScope(project)
            val psiClass = JavaPsiFacade.getInstance(project).findClass(className, scope)
            psiClass?.let { ModuleUtilCore.findModuleForPsiElement(it) }
        }

        if (config is com.intellij.execution.configurations.ModuleBasedConfiguration<*, *>) {
            module?.let { config.setModule(it) }
        }
        try {
            val pd = config.javaClass.getMethod("getPersistentData").invoke(config) ?: return
            pd.javaClass.getField("MAIN_CLASS_NAME").set(pd, className)
            if (methodName != null) {
                pd.javaClass.getField("METHOD_NAME").set(pd, methodName)
                pd.javaClass.getField("TEST_OBJECT").set(pd, "method")
            } else {
                pd.javaClass.getField("TEST_OBJECT").set(pd, "class")
            }
        } catch (_: Exception) {}
    }

    private fun setVmParameters(config: com.intellij.execution.configurations.RunConfiguration, params: String) {
        try {
            val method = config.javaClass.getMethod("setVMParameters", String::class.java)
            method.invoke(config, params)
        } catch (_: Exception) {}
    }

    private fun getVmParameters(config: com.intellij.execution.configurations.RunConfiguration): String {
        return try {
            val method = config.javaClass.getMethod("getVMParameters")
            method.invoke(config) as? String ?: ""
        } catch (_: Exception) { "" }
    }
}
