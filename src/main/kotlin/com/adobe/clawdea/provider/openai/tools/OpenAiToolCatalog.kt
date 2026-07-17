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
package com.adobe.clawdea.provider.openai.tools

import com.adobe.clawdea.mcp.McpToolRouter
import com.adobe.clawdea.provider.openai.agent.OpenAiFunctionDefinition
import com.adobe.clawdea.provider.openai.agent.OpenAiToolDefinition
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Maps MCP tool definitions to OpenAI function-tool schemas, and dispatches
 * agent tool calls with argument validation.
 */
class OpenAiToolCatalog(
    private val mcpDefs: List<McpToolRouter.ToolDef>,
    @Suppress("UNUSED_PARAMETER") private val hostTools: List<Any>, // future expansion
) {
    private val mcpByName = mcpDefs.associateBy { it.name }

    fun definitions(): List<OpenAiToolDefinition> {
        return mcpDefs.map { def ->
            OpenAiToolDefinition(
                type = "function",
                function = OpenAiFunctionDefinition(
                    name = def.name,
                    description = def.description,
                    parameters = buildJsonSchema(def.properties, def.required),
                ),
            )
        }
    }

    private fun buildJsonSchema(
        properties: List<Triple<String, String, String>>,
        required: List<String>,
    ): JsonObject = objectSchema(properties, required)

    /**
     * Dispatch a tool call from the model. Validates arguments and routes to
     * the MCP handler. Returns a structured result.
     */
    fun dispatch(toolCallId: String, toolName: String, argumentsJson: String): ToolExecutionResult {
        val def = mcpByName[toolName]
            ?: return ToolExecutionResult(
                toolCallId = toolCallId,
                content = "Unknown tool: $toolName",
                isError = true,
            )

        val arguments = try {
            parseArguments(argumentsJson)
        } catch (e: Exception) {
            return ToolExecutionResult(
                toolCallId = toolCallId,
                content = "Malformed JSON arguments: ${e.message}",
                isError = true,
            )
        }

        // Validate required parameters
        for (req in def.required) {
            if (req !in arguments) {
                return ToolExecutionResult(
                    toolCallId = toolCallId,
                    content = "missing required parameter: $req",
                    isError = true,
                )
            }
        }

        val mcpResult = def.handler(arguments)
        return ToolExecutionResult(
            toolCallId = toolCallId,
            content = mcpResult.text,
            isError = mcpResult.isError,
        )
    }

    private fun parseArguments(json: String): Map<String, String> {
        val obj = JsonParser.parseString(json).asJsonObject
        val result = mutableMapOf<String, String>()
        for ((key, value) in obj.entrySet()) {
            result[key] = when {
                // Scalars (string/number/boolean) coerce to their JSON string form. Numbers and
                // booleans arrive frequently once tools are advertised; asString handles all three.
                value.isJsonPrimitive -> value.asString
                // Objects/arrays pass through as their JSON text so structured tool handlers can
                // re-parse them; MCP handlers expecting a scalar will surface their own error.
                value.isJsonObject || value.isJsonArray -> value.toString()
                // JSON null carries no value.
                else -> ""
            }
        }
        return result
    }

    companion object {
        /**
         * OpenAI function-tool schemas for the host tools the [ProductionToolExecutor] dispatches
         * directly (never through MCP): `Bash` (→ [HostShellTool]) and `apply_patch`
         * (→ [HostPatchTool]). Advertised alongside [definitions] so agentic models are told these
         * tools exist and emit `tool_calls` the executor can actually route. Schemas mirror the
         * arguments the executor parses.
         */
        fun hostToolDefinitions(): List<OpenAiToolDefinition> = listOf(
            OpenAiToolDefinition(
                type = "function",
                function = OpenAiFunctionDefinition(
                    name = "Bash",
                    description = "Run a shell command in the project working directory and return " +
                        "its combined stdout/stderr and exit code. Bounded by a timeout and output cap.",
                    parameters = objectSchema(
                        properties = listOf(
                            Triple("command", "string", "The shell command line to execute."),
                        ),
                        required = listOf("command"),
                    ),
                ),
            ),
            OpenAiToolDefinition(
                type = "function",
                function = OpenAiFunctionDefinition(
                    name = "apply_patch",
                    description = "Apply an edit to a file within the project. Provide the target " +
                        "file path, the full current (original) content, and the full proposed " +
                        "content. The edit is validated and routed through diff-gated review.",
                    parameters = objectSchema(
                        properties = listOf(
                            Triple("file_path", "string", "Absolute path of the file to edit (must be inside the project)."),
                            Triple("original_content", "string", "The full current content of the file (empty string for a new file)."),
                            Triple("proposed_content", "string", "The full new content the file should have after the edit."),
                        ),
                        required = listOf("file_path", "original_content", "proposed_content"),
                    ),
                ),
            ),
        )

        private fun objectSchema(
            properties: List<Triple<String, String, String>>,
            required: List<String>,
        ): JsonObject {
            val schema = JsonObject()
            schema.addProperty("type", "object")

            val propsObj = JsonObject()
            for ((name, type, desc) in properties) {
                val propSchema = JsonObject()
                propSchema.addProperty("type", type)
                propSchema.addProperty("description", desc)
                propsObj.add(name, propSchema)
            }
            schema.add("properties", propsObj)

            if (required.isNotEmpty()) {
                val reqArray = JsonArray()
                required.forEach { reqArray.add(it) }
                schema.add("required", reqArray)
            }

            return schema
        }
    }
}

/**
 * Result of executing a tool call.
 */
data class ToolExecutionResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean,
)
