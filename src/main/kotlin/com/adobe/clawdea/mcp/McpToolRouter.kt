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
package com.adobe.clawdea.mcp

/**
 * Registry of MCP tools. Maps tool names to handler functions and
 * generates the tools/list response.
 */
class McpToolRouter {

    data class ToolDef(
        val name: String,
        val description: String,
        val properties: List<Triple<String, String, String>>,
        val required: List<String>,
        val handler: (Map<String, String>) -> ToolResult,
    )

    data class ToolResult(
        val text: String,
        val isError: Boolean = false,
    )

    private val tools = mutableMapOf<String, ToolDef>()

    fun register(
        name: String,
        description: String,
        properties: List<Triple<String, String, String>>,
        required: List<String> = emptyList(),
        handler: (Map<String, String>) -> ToolResult,
    ) {
        tools[name] = ToolDef(name, description, properties, required, handler)
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun dispatch(toolName: String, arguments: Map<String, String>): ToolResult {
        val tool = tools[toolName]
            ?: return ToolResult("Unknown tool: $toolName", isError = true)
        return try {
            tool.handler(arguments)
        } catch (e: Exception) {
            ToolResult("Tool '$toolName' failed: ${e.message}", isError = true)
        }
    }

    fun toolsListJson(): String {
        val schemas = tools.values.joinToString(",") { tool ->
            McpProtocol.toolSchema(tool.name, tool.description, tool.properties, tool.required)
        }
        return "[$schemas]"
    }
}
