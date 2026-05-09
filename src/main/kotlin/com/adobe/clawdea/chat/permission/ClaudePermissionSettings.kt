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
package com.adobe.clawdea.chat.permission

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class ClaudePermissionSettings(
    val allow: List<ClaudePermissionRule> = emptyList(),
    val deny: List<ClaudePermissionRule> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class ClaudePermissionRule(
    val raw: String,
    val toolName: String,
    val pattern: String?,
    val source: Path? = null,
) {
    fun matches(toolName: String, inputJson: String): Boolean {
        if (this.toolName != toolName) return false
        val rulePattern = pattern ?: return true
        val value = PermissionToolInput.extractSpecifier(toolName, inputJson) ?: return false
        return if (rulePattern.any { it == '*' || it == '?' }) {
            globToRegex(rulePattern).matches(value)
        } else {
            rulePattern == value
        }
    }

    fun withSource(source: Path): ClaudePermissionRule = copy(source = source)

    companion object {
        private val RULE = Regex("""^([A-Za-z0-9_.:-]+)(?:\((.*)\))?$""")

        fun parse(raw: String): ClaudePermissionRule? {
            val trimmed = raw.trim()
            val match = RULE.matchEntire(trimmed) ?: return null
            val toolName = match.groupValues[1]
            val pattern = match.groups[2]?.value
            return ClaudePermissionRule(raw = trimmed, toolName = toolName, pattern = pattern)
        }

        private fun globToRegex(pattern: String): Regex {
            val builder = StringBuilder("^")
            for (char in pattern) {
                when (char) {
                    '*' -> builder.append(".*")
                    '?' -> builder.append(".")
                    else -> builder.append(Regex.escape(char.toString()))
                }
            }
            builder.append('$')
            return builder.toString().toRegex()
        }
    }
}

class ClaudePermissionSettingsReader(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
    private val projectBasePath: Path,
) {
    fun read(): ClaudePermissionSettings {
        val allow = mutableListOf<ClaudePermissionRule>()
        val deny = mutableListOf<ClaudePermissionRule>()
        val warnings = mutableListOf<String>()
        for (path in settingsFiles()) {
            readFile(path, allow, deny, warnings)
        }
        return ClaudePermissionSettings(allow = allow, deny = deny, warnings = warnings)
    }

    private fun settingsFiles(): List<Path> = listOf(
        userHome.resolve(".claude/settings.json"),
        projectBasePath.resolve(".claude/settings.json"),
        projectBasePath.resolve(".claude/settings.local.json"),
    )

    private fun readFile(
        path: Path,
        allow: MutableList<ClaudePermissionRule>,
        deny: MutableList<ClaudePermissionRule>,
        warnings: MutableList<String>,
    ) {
        if (!path.exists()) return
        val root = try {
            JsonParser.parseString(path.readText())
        } catch (e: Exception) {
            warnings.add("${path}: could not parse Claude settings (${e.message})")
            return
        }
        if (!root.isJsonObject) {
            warnings.add("${path}: Claude settings root is not an object")
            return
        }
        val permissions = root.asJsonObject.get("permissions") ?: return
        if (!permissions.isJsonObject) {
            warnings.add("${path}: permissions has an unsupported shape")
            return
        }
        val permissionsObject = permissions.asJsonObject
        readRules(path, permissionsObject, "allow", allow, warnings)
        readRules(path, permissionsObject, "deny", deny, warnings)
    }

    private fun readRules(
        path: Path,
        permissions: JsonObject,
        key: String,
        destination: MutableList<ClaudePermissionRule>,
        warnings: MutableList<String>,
    ) {
        val value = permissions.get(key) ?: return
        if (!value.isJsonArray) {
            warnings.add("${path}: permissions.$key is not an array")
            return
        }
        for ((index, entry) in value.asJsonArray.withIndex()) {
            if (!entry.isJsonPrimitive || !entry.asJsonPrimitive.isString) {
                warnings.add("${path}: permissions.$key[$index] is not a string rule")
                continue
            }
            val raw = entry.asString
            val rule = ClaudePermissionRule.parse(raw)
            if (rule == null) {
                warnings.add("${path}: permissions.$key[$index] is not a supported rule")
            } else {
                destination.add(rule.withSource(path))
            }
        }
    }
}

class PermissionPolicy(
    private val settingsProvider: () -> ClaudePermissionSettings,
) {
    data class Result(
        val decision: Decision,
        val rule: ClaudePermissionRule? = null,
        val warnings: List<String> = emptyList(),
    )

    enum class Decision { ALLOW, DENY, ASK }

    fun evaluate(toolName: String, inputJson: String): Result {
        val settings = try {
            settingsProvider()
        } catch (e: Exception) {
            return Result(Decision.ASK, warnings = listOf("Could not read Claude permission settings: ${e.message}"))
        }
        val deny = settings.deny.firstOrNull { it.matches(toolName, inputJson) }
        if (deny != null) return Result(Decision.DENY, deny, settings.warnings)
        val allow = settings.allow.firstOrNull { it.matches(toolName, inputJson) }
        if (allow != null) return Result(Decision.ALLOW, allow, settings.warnings)
        return Result(Decision.ASK, warnings = settings.warnings)
    }
}

class ClaudePermissionSettingsWriter(private val projectBasePath: Path) {
    data class Result(val success: Boolean, val message: String? = null)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val localSettings: Path get() = projectBasePath.resolve(".claude/settings.local.json")

    fun appendAllowRule(rule: String): Result {
        val parsedRule = ClaudePermissionRule.parse(rule)
            ?: return Result(false, "Unsupported Claude permission rule: $rule")
        val file = localSettings
        return try {
            Files.createDirectories(file.parent)
            val root = if (file.exists()) {
                val parsed = JsonParser.parseString(file.readText())
                if (!parsed.isJsonObject) return Result(false, "Claude local settings root is not an object")
                parsed.asJsonObject
            } else {
                JsonObject()
            }
            val permissions = root.get("permissions")?.let {
                if (!it.isJsonObject) return Result(false, "Claude local settings permissions has an unsupported shape")
                it.asJsonObject
            } ?: JsonObject().also { root.add("permissions", it) }
            val allow = permissions.get("allow")?.let {
                if (!it.isJsonArray) return Result(false, "Claude local settings permissions.allow is not an array")
                it.asJsonArray
            } ?: JsonArray().also { permissions.add("allow", it) }
            val alreadyPresent = allow.any { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == parsedRule.raw }
            if (!alreadyPresent) {
                allow.add(parsedRule.raw)
            }
            file.writeText(gson.toJson(root) + "\n")
            Result(true)
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }
}

internal object PermissionToolInput {
    fun extractSpecifier(toolName: String, inputJson: String): String? {
        val input = try {
            JsonParser.parseString(inputJson)
        } catch (_: Exception) {
            return null
        }
        if (!input.isJsonObject) return null
        val obj = input.asJsonObject
        val keys = when (toolName) {
            "Bash" -> listOf("command")
            "Read", "Write", "Edit", "MultiEdit" -> listOf("file_path", "path")
            "NotebookRead", "NotebookEdit" -> listOf("notebook_path", "file_path")
            "WebFetch" -> listOf("url")
            "WebSearch" -> listOf("query")
            else -> listOf("command", "file_path", "path", "url", "query")
        }
        for (key in keys) {
            val value = obj.get(key)
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                return value.asString
            }
        }
        return null
    }
}
