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
package com.adobe.clawdea.profiling.commands

import com.adobe.clawdea.commands.CommandCategory
import com.adobe.clawdea.commands.CommandInfo
import com.adobe.clawdea.commands.handlers.BridgeExpandingHandler

object ProfileCommandHandler {

    fun create(): BridgeExpandingHandler = BridgeExpandingHandler(
        info = CommandInfo("/profile", "Start a profiling session or import a recording", CommandCategory.BRIDGE),
    ) { args ->
        val trimmed = args.trim()
        when {
            trimmed.isEmpty() -> interactivePrompt()
            trimmed.startsWith("import ") -> importPrompt(trimmed.removePrefix("import ").trim())
            trimmed.startsWith("test ") -> directPrompt("test", trimmed.removePrefix("test ").trim())
            trimmed.startsWith("run ") -> directPrompt("run", trimmed.removePrefix("run ").trim())
            trimmed.startsWith("attach ") -> directPrompt("attach", trimmed.removePrefix("attach ").trim())
            else -> interactivePrompt()
        }
    }

    private fun interactivePrompt(): String = """
        The user wants to profile something. Ask them what they want to profile:
        - A Run/Debug configuration (by name)
        - A specific test method (fully qualified, e.g. com.example.FooTest#testBar)
        - An already-running JVM (by PID)
        - Or import an existing .jfr/.hprof file

        Once you know the target, call the `profiling_start` MCP tool with the appropriate target argument.
        After the session completes (the process exits), call `profiling_analyze_cpu` and `profiling_analyze_allocations`
        on the resulting recording_id. Explain the findings and propose fixes with `propose_edit` where appropriate.
    """.trimIndent()

    private fun importPrompt(path: String): String = """
        Import the recording at: $path

        Call the `profiling_import` MCP tool with path="$path".
        After import, determine the file type:
        - For .jfr: call `profiling_analyze_cpu` and `profiling_analyze_allocations`
        - For .hprof: call `profiling_analyze_leaks`
        Explain the findings and propose fixes with `propose_edit` where source_location.in_project is true.
    """.trimIndent()

    private fun directPrompt(mode: String, target: String): String {
        val targetArg = when (mode) {
            "test" -> "test:$target"
            "attach" -> "pid:$target"
            else -> target
        }
        return """
            Start profiling with target="$targetArg".

            Call the `profiling_start` MCP tool with target="$targetArg".
            Wait for the session to complete (process exits), then call `profiling_analyze_cpu` and `profiling_analyze_allocations`.
            Explain the findings and propose fixes with `propose_edit` where appropriate.
        """.trimIndent()
    }
}
