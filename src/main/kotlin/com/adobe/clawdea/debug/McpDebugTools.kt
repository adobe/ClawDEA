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
package com.adobe.clawdea.debug

import com.adobe.clawdea.mcp.McpToolRouter
import com.intellij.openapi.project.Project

/**
 * MCP tool handlers for debugging operations.
 * Wires DebugBridge into the MCP server with 21 tools across 4 categories:
 * - Session (5): launch, attach, status, stop
 * - Breakpoints (5): set, remove, disable, enable, list
 * - Execution (6): resume, pause, step over/into/out, run to cursor
 * - Inspection (5): frames, variables, expand, evaluate, set value
 */
class McpDebugTools(private val project: Project) {

    private val bridge by lazy { DebugBridge.getInstance(project) }

    fun registerAll(router: McpToolRouter) {
        // --- Session (5) ---
        router.register(
            name = "debug_launch",
            description = "Launch a debug session using an existing Run/Debug configuration. Returns session status (blocking until started).",
            properties = listOf(
                Triple("config_name", "string", "Name of the Run/Debug configuration to launch"),
            ),
            required = listOf("config_name"),
            handler = ::handleLaunch,
        )
        router.register(
            name = "debug_launch_adhoc",
            description = "Launch an ad-hoc debug session without a pre-configured Run configuration. Supports Java applications and JUnit tests. Returns session status (blocking until started).",
            properties = listOf(
                Triple("type", "string", "Session type: JAVA_APP, JAVA_TEST, JS_DEBUG, or NODE"),
                Triple("target", "string", "Fully qualified class name (Java) or file path (JS/Node)"),
                Triple("args", "string", "Optional program arguments"),
                Triple("env", "string", "Optional environment variables (JSON map)"),
            ),
            required = listOf("type", "target"),
            handler = ::handleLaunchAdHoc,
        )
        router.register(
            name = "debug_attach",
            description = "Attach debugger to a running process via remote debugging protocol. Blocks until attached. Supports Java (JDWP) and Node.js (Chrome DevTools).",
            properties = listOf(
                Triple("host", "string", "Hostname or IP address of the target process"),
                Triple("port", "string", "Debug port (e.g., 5005 for Java, 9229 for Node)"),
                Triple("runtime", "string", "Runtime type: JAVA or NODE"),
            ),
            required = listOf("host", "port", "runtime"),
            handler = ::handleAttach,
        )
        router.register(
            name = "debug_get_session",
            description = "Get the status of the active debug session. Returns session type, suspended state, and current position (file, line, method).",
            properties = emptyList(),
            required = emptyList(),
            handler = ::handleGetSession,
        )
        router.register(
            name = "debug_stop",
            description = "Stop the active debug session. Removes all Claude-owned breakpoints and re-enables user breakpoints that were disabled.",
            properties = emptyList(),
            required = emptyList(),
            handler = ::handleStop,
        )

        // --- Breakpoints (5) ---
        router.register(
            name = "debug_set_breakpoint",
            description = "Set a line breakpoint at the specified file and line. The breakpoint is owned by Claude and will be removed when the session stops. Optionally specify a condition or log expression.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
                Triple("line", "string", "1-based line number"),
                Triple("condition", "string", "Optional boolean expression (breakpoint only triggers if true)"),
                Triple("log_expression", "string", "Optional expression to log when breakpoint hits (without suspending)"),
            ),
            required = listOf("file", "line"),
            handler = ::handleSetBreakpoint,
        )
        router.register(
            name = "debug_remove_breakpoint",
            description = "Remove a Claude-owned breakpoint at the specified location. Cannot remove user-owned breakpoints (use debug_disable_breakpoint instead).",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
                Triple("line", "string", "1-based line number"),
            ),
            required = listOf("file", "line"),
            handler = ::handleRemoveBreakpoint,
        )
        router.register(
            name = "debug_disable_breakpoint",
            description = "Temporarily disable a breakpoint at the specified location. Works for both Claude-owned and user-owned breakpoints. User breakpoints will be re-enabled when the session stops.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
                Triple("line", "string", "1-based line number"),
            ),
            required = listOf("file", "line"),
            handler = ::handleDisableBreakpoint,
        )
        router.register(
            name = "debug_enable_breakpoint",
            description = "Enable a previously disabled breakpoint at the specified location.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
                Triple("line", "string", "1-based line number"),
            ),
            required = listOf("file", "line"),
            handler = ::handleEnableBreakpoint,
        )
        router.register(
            name = "debug_list_breakpoints",
            description = "List all breakpoints in the project. Returns file path, line, enabled state, ownership (claude/user), and optional condition/log expressions.",
            properties = emptyList(),
            required = emptyList(),
            handler = ::handleListBreakpoints,
        )

        // --- Execution (6) ---
        router.register(
            name = "debug_resume",
            description = "Resume execution of the suspended program. Blocks until the program suspends again (at a breakpoint or by pause) or the session ends. Returns suspend info (file, line, method) or timeout.",
            properties = emptyList(),
            required = emptyList(),
            handler = ::handleResume,
        )
        router.register(
            name = "debug_pause",
            description = "Pause the running program. Blocks until suspended. Returns suspend info (file, line, method) or timeout.",
            properties = emptyList(),
            required = emptyList(),
            handler = ::handlePause,
        )
        router.register(
            name = "debug_step_over",
            description = "Execute the current line and suspend at the next line in the same method. Blocks until suspended. Returns suspend info or timeout.",
            properties = emptyList(),
            required = emptyList(),
            handler = ::handleStepOver,
        )
        router.register(
            name = "debug_step_into",
            description = "Step into the method call on the current line. If no method call, equivalent to step_over. Blocks until suspended. Returns suspend info or timeout.",
            properties = emptyList(),
            required = emptyList(),
            handler = ::handleStepInto,
        )
        router.register(
            name = "debug_step_out",
            description = "Execute until the current method returns, then suspend in the caller. Blocks until suspended. Returns suspend info or timeout.",
            properties = emptyList(),
            required = emptyList(),
            handler = ::handleStepOut,
        )
        router.register(
            name = "debug_run_to_cursor",
            description = "Resume execution until the specified file and line is reached, then suspend. Blocks until suspended or timeout. Returns suspend info or timeout.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
                Triple("line", "string", "1-based line number"),
            ),
            required = listOf("file", "line"),
            handler = ::handleRunToCursor,
        )

        // --- Inspection (5) ---
        router.register(
            name = "debug_get_frames",
            description = "Get the current call stack frames. Returns a list of frames with file, line, method, and class name. Only works when the program is suspended.",
            properties = listOf(
                Triple("thread_id", "string", "Optional thread ID (defaults to current thread)"),
            ),
            required = emptyList(),
            handler = ::handleGetFrames,
        )
        router.register(
            name = "debug_get_variables",
            description = "Get all variables visible in the specified stack frame. Returns name, type, value, and expandable flag. Only works when the program is suspended.",
            properties = listOf(
                Triple("frame_index", "string", "0-based frame index (0 = top frame, default 0)"),
            ),
            required = emptyList(),
            handler = ::handleGetVariables,
        )
        router.register(
            name = "debug_expand_variable",
            description = "Expand a composite variable (object, array, collection) to see its fields/elements. Returns child variables with name, type, value, and expandable flag. Only works when the program is suspended.",
            properties = listOf(
                Triple("frame_index", "string", "0-based frame index (default 0)"),
                Triple("path", "string", "Variable path (e.g., 'myObject' or 'myArray[0].field')"),
            ),
            required = listOf("path"),
            handler = ::handleExpandVariable,
        )
        router.register(
            name = "debug_evaluate",
            description = "Evaluate an expression in the context of the specified stack frame. Returns type, value, and expandable flag. Only works when the program is suspended.",
            properties = listOf(
                Triple("expression", "string", "Expression to evaluate (e.g., 'x + y', 'myList.size()')"),
                Triple("frame_index", "string", "0-based frame index (default 0)"),
            ),
            required = listOf("expression"),
            handler = ::handleEvaluate,
        )
        router.register(
            name = "debug_set_value",
            description = "Set the value of a variable in the specified stack frame. Only works when the program is suspended.",
            properties = listOf(
                Triple("frame_index", "string", "0-based frame index (default 0)"),
                Triple("var_name", "string", "Variable name to modify"),
                Triple("value", "string", "New value (as a string)"),
            ),
            required = listOf("var_name", "value"),
            handler = ::handleSetValue,
        )
    }

    // --- Session handlers ---

    private fun handleLaunch(args: Map<String, String>): McpToolRouter.ToolResult {
        val configName = args["config_name"]
            ?: return McpToolRouter.ToolResult("Missing 'config_name' argument", isError = true)
        val status = bridge.launch(configName)
        return McpToolRouter.ToolResult(status.toText(), isError = !status.active)
    }

    private fun handleLaunchAdHoc(args: Map<String, String>): McpToolRouter.ToolResult {
        val typeStr = args["type"]
            ?: return McpToolRouter.ToolResult("Missing 'type' argument", isError = true)
        val type = try {
            AdHocType.valueOf(typeStr)
        } catch (e: IllegalArgumentException) {
            return McpToolRouter.ToolResult("Invalid type: $typeStr. Must be one of: JAVA_APP, JAVA_TEST, JS_DEBUG, NODE", isError = true)
        }
        val target = args["target"]
            ?: return McpToolRouter.ToolResult("Missing 'target' argument", isError = true)
        val argsParam = args["args"]
        val env = args["env"]?.let { parseEnvJson(it) } ?: emptyMap()
        val status = bridge.launchAdHoc(type, target, argsParam, env)
        return McpToolRouter.ToolResult(status.toText(), isError = !status.active)
    }

    private fun handleAttach(args: Map<String, String>): McpToolRouter.ToolResult {
        val host = args["host"] ?: return McpToolRouter.ToolResult("Missing 'host' argument", isError = true)
        val portResult = parsePort(args)
        if (portResult.isFailure) {
            return McpToolRouter.ToolResult(portResult.exceptionOrNull()!!.message!!, isError = true)
        }
        val port = portResult.getOrThrow()
        val runtimeStr = args["runtime"]
            ?: return McpToolRouter.ToolResult("Missing 'runtime' argument", isError = true)
        val runtime = try {
            AttachRuntime.valueOf(runtimeStr)
        } catch (e: IllegalArgumentException) {
            return McpToolRouter.ToolResult("Invalid runtime: $runtimeStr. Must be JAVA or NODE", isError = true)
        }
        val status = bridge.attach(host, port, runtime)
        return McpToolRouter.ToolResult(status.toText(), isError = !status.active)
    }

    private fun handleGetSession(args: Map<String, String>): McpToolRouter.ToolResult {
        val status = bridge.getSession()
        return McpToolRouter.ToolResult(status.toText(), isError = !status.active)
    }

    private fun handleStop(args: Map<String, String>): McpToolRouter.ToolResult {
        val message = bridge.stop()
        return McpToolRouter.ToolResult(message)
    }

    // --- Breakpoint handlers ---

    private fun handleSetBreakpoint(args: Map<String, String>): McpToolRouter.ToolResult {
        val file = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)
        val lineResult = parseLine(args)
        if (lineResult.isFailure) {
            return McpToolRouter.ToolResult(lineResult.exceptionOrNull()!!.message!!, isError = true)
        }
        val line = lineResult.getOrThrow()
        val condition = args["condition"]
        val logExpression = args["log_expression"]
        val message = bridge.addBreakpoint(file, line, condition, logExpression)
        return McpToolRouter.ToolResult(message)
    }

    private fun handleRemoveBreakpoint(args: Map<String, String>): McpToolRouter.ToolResult {
        val file = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)
        val lineResult = parseLine(args)
        if (lineResult.isFailure) {
            return McpToolRouter.ToolResult(lineResult.exceptionOrNull()!!.message!!, isError = true)
        }
        val line = lineResult.getOrThrow()
        val message = bridge.removeBreakpoint(file, line)
        return McpToolRouter.ToolResult(message)
    }

    private fun handleDisableBreakpoint(args: Map<String, String>): McpToolRouter.ToolResult {
        val file = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)
        val lineResult = parseLine(args)
        if (lineResult.isFailure) {
            return McpToolRouter.ToolResult(lineResult.exceptionOrNull()!!.message!!, isError = true)
        }
        val line = lineResult.getOrThrow()
        val message = bridge.disableBreakpoint(file, line)
        return McpToolRouter.ToolResult(message)
    }

    private fun handleEnableBreakpoint(args: Map<String, String>): McpToolRouter.ToolResult {
        val file = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)
        val lineResult = parseLine(args)
        if (lineResult.isFailure) {
            return McpToolRouter.ToolResult(lineResult.exceptionOrNull()!!.message!!, isError = true)
        }
        val line = lineResult.getOrThrow()
        val message = bridge.enableBreakpoint(file, line)
        return McpToolRouter.ToolResult(message)
    }

    private fun handleListBreakpoints(args: Map<String, String>): McpToolRouter.ToolResult {
        val breakpoints = bridge.listBreakpoints()
        if (breakpoints.isEmpty()) {
            return McpToolRouter.ToolResult("No breakpoints set.")
        }
        val text = breakpoints.joinToString("\n") { it.toText() }
        return McpToolRouter.ToolResult(text)
    }

    // --- Execution handlers ---

    private fun handleResume(args: Map<String, String>): McpToolRouter.ToolResult {
        val suspendInfo = bridge.resume()
        return McpToolRouter.ToolResult(formatSuspendInfo(suspendInfo))
    }

    private fun handlePause(args: Map<String, String>): McpToolRouter.ToolResult {
        val suspendInfo = bridge.pause()
        return McpToolRouter.ToolResult(formatSuspendInfo(suspendInfo))
    }

    private fun handleStepOver(args: Map<String, String>): McpToolRouter.ToolResult {
        val suspendInfo = bridge.stepOver()
        return McpToolRouter.ToolResult(formatSuspendInfo(suspendInfo))
    }

    private fun handleStepInto(args: Map<String, String>): McpToolRouter.ToolResult {
        val suspendInfo = bridge.stepInto()
        return McpToolRouter.ToolResult(formatSuspendInfo(suspendInfo))
    }

    private fun handleStepOut(args: Map<String, String>): McpToolRouter.ToolResult {
        val suspendInfo = bridge.stepOut()
        return McpToolRouter.ToolResult(formatSuspendInfo(suspendInfo))
    }

    private fun handleRunToCursor(args: Map<String, String>): McpToolRouter.ToolResult {
        val file = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)
        val lineResult = parseLine(args)
        if (lineResult.isFailure) {
            return McpToolRouter.ToolResult(lineResult.exceptionOrNull()!!.message!!, isError = true)
        }
        val line = lineResult.getOrThrow()
        val suspendInfo = bridge.runToCursor(file, line)
        return McpToolRouter.ToolResult(formatSuspendInfo(suspendInfo))
    }

    // --- Inspection handlers ---

    private fun handleGetFrames(args: Map<String, String>): McpToolRouter.ToolResult {
        val threadId = args["thread_id"]?.toLongOrNull()
        val frames = bridge.getFrames(threadId)
        if (frames.isEmpty()) {
            return McpToolRouter.ToolResult("No frames available. Program must be suspended.", isError = true)
        }
        val text = frames.joinToString("\n") { it.toText() }
        return McpToolRouter.ToolResult(text)
    }

    private fun handleGetVariables(args: Map<String, String>): McpToolRouter.ToolResult {
        val frameIndex = parseFrameIndex(args)
        val variables = bridge.getVariables(frameIndex)
        if (variables.isEmpty()) {
            return McpToolRouter.ToolResult("No variables available. Program must be suspended.", isError = true)
        }
        val text = variables.joinToString("\n") { it.toText() }
        return McpToolRouter.ToolResult(text)
    }

    private fun handleExpandVariable(args: Map<String, String>): McpToolRouter.ToolResult {
        val frameIndex = parseFrameIndex(args)
        val path = args["path"] ?: return McpToolRouter.ToolResult("Missing 'path' argument", isError = true)
        val children = bridge.expandVariable(frameIndex, path)
        if (children.isEmpty()) {
            return McpToolRouter.ToolResult("Variable '$path' is not expandable or not found.", isError = true)
        }
        val text = children.joinToString("\n") { it.toText() }
        return McpToolRouter.ToolResult(text)
    }

    private fun handleEvaluate(args: Map<String, String>): McpToolRouter.ToolResult {
        val expression = args["expression"]
            ?: return McpToolRouter.ToolResult("Missing 'expression' argument", isError = true)
        val frameIndex = parseFrameIndex(args)
        val result = bridge.evaluate(expression, frameIndex)
        return McpToolRouter.ToolResult(result.toText(), isError = result.isError)
    }

    private fun handleSetValue(args: Map<String, String>): McpToolRouter.ToolResult {
        val frameIndex = parseFrameIndex(args)
        val varName = args["var_name"]
            ?: return McpToolRouter.ToolResult("Missing 'var_name' argument", isError = true)
        val value = args["value"]
            ?: return McpToolRouter.ToolResult("Missing 'value' argument", isError = true)
        val message = bridge.setValue(frameIndex, varName, value)
        return McpToolRouter.ToolResult(message)
    }

    // --- Companion helpers ---

    companion object {
        fun parseLine(args: Map<String, String>): Result<Int> {
            val lineStr = args["line"]
                ?: return Result.failure(IllegalArgumentException("Missing 'line' argument"))
            val line = lineStr.toIntOrNull()
                ?: return Result.failure(IllegalArgumentException("Invalid 'line' argument: must be a number"))
            return Result.success(line)
        }

        fun parsePort(args: Map<String, String>): Result<Int> {
            val portStr = args["port"]
                ?: return Result.failure(IllegalArgumentException("Missing 'port' argument"))
            val port = portStr.toIntOrNull()
                ?: return Result.failure(IllegalArgumentException("Invalid 'port' argument: must be a number"))
            if (port !in 1..65535) {
                return Result.failure(IllegalArgumentException("Invalid 'port' argument: must be 1-65535"))
            }
            return Result.success(port)
        }

        fun parseFrameIndex(args: Map<String, String>): Int {
            return args["frame_index"]?.toIntOrNull() ?: 0
        }

        fun formatSuspendInfo(info: SuspendInfo?): String {
            if (info == null) {
                return "Timeout — program is still running."
            }
            if (info.exitCode != -1) {
                return "Program exited with code ${info.exitCode}."
            }
            val pos = info.file?.let { "$it:${info.line}" } ?: "unknown"
            val method = info.method?.let { " in $it" } ?: ""
            return "Suspended at $pos$method"
        }

        private fun parseEnvJson(json: String): Map<String, String> {
            // Simple JSON map parser for {"key":"value"} format
            // This is intentionally basic — production would use Gson
            return try {
                json.trim().removeSurrounding("{", "}")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"").split("\":\"") }
                    .filter { it.size == 2 }
                    .associate { it[0] to it[1] }
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
}
