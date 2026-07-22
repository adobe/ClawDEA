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

import com.adobe.clawdea.commands.handlers.SkillHandler
import com.adobe.clawdea.skills.SkillInfo

/**
 * Host tool that lets an OpenAI-compatible agent invoke a skill mid-turn.
 *
 * Resolves the requested skill against the session's discovered [skills] (by qualified name,
 * short name, or alias — case-insensitive, tolerating an optional leading "/"), reads its
 * SKILL.md, and returns the content wrapped identically to the user-typed fallback
 * ([SkillHandler.buildFallbackMessage]) so there is one canonical rendering. Unknown names and
 * unreadable files fail soft as an error [ToolExecutionResult] — never throws.
 */
class SkillTool(private val skills: List<SkillInfo>) {

    fun execute(name: String, args: String?, toolUseId: String): ToolExecutionResult {
        val skill = resolve(name)
            ?: return ToolExecutionResult(
                toolCallId = toolUseId,
                content = "Unknown skill: $name. Available skills: " +
                    if (skills.isEmpty()) "(none)"
                    else skills.joinToString(", ") { it.qualifiedName },
                isError = true,
            )

        return try {
            ToolExecutionResult(
                toolCallId = toolUseId,
                content = SkillHandler.buildFallbackMessage(skill, args ?: ""),
                isError = false,
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolCallId = toolUseId,
                content = "Failed to read skill '${skill.qualifiedName}': ${e.message}",
                isError = true,
            )
        }
    }

    private fun resolve(name: String): SkillInfo? {
        val key = name.trim().removePrefix("/").lowercase()
        if (key.isEmpty()) return null
        return skills.firstOrNull { s ->
            s.qualifiedName.lowercase() == key ||
                s.name.lowercase() == key ||
                s.aliases.any { it.removePrefix("/").lowercase() == key }
        }
    }
}
