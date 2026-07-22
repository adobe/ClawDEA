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
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.knowledge.prompts.PromptResource
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Reads the bundled `wiki-librarian` and `wiki-author` agent definitions
 * from the plugin classpath and produces the JSON payload accepted by
 * Claude Code's `--agents <json>` CLI flag.
 *
 * The wiki-author body contains `{{wiki-page-invariant}}` and
 * `{{wiki-page-navigation}}` placeholders that are substituted with the
 * canonical templates from `/prompts/wiki-page-invariant.md` and
 * `/prompts/wiki-page-navigation.md` so the subagent's output matches
 * the pages produced by `/seed-wiki`.
 *
 * Frontmatter format (resource at `/agents/<name>.md`):
 *   ---
 *   name: <id>
 *   description: <single line>
 *   tools: <csv of tool names>     # optional
 *   ---
 *   <prompt body>
 */
object WikiAgentsArg {

    private const val LIBRARIAN_PATH = "/agents/wiki-librarian.md"
    private const val AUTHOR_PATH = "/agents/wiki-author.md"

    fun buildAuthorOnlyJson(): String {
        val root = JsonObject()
        addAgentTo(root, AUTHOR_PATH)
        return root.toString()
    }

    /**
     * The wiki-author agent's fully-substituted prompt body (same text the Claude path injects via
     * `--agents`). Reused as the system prompt for the non-Claude (openai-compatible) agentic path,
     * which cannot use `--agents`. Throws if the bundled resource is missing (a packaging defect).
     */
    fun authorPromptBody(): String {
        val raw = WikiAgentsArg::class.java.getResourceAsStream(AUTHOR_PATH)
            ?: throw IllegalStateException("Plugin resource not found: $AUTHOR_PATH")
        val text = raw.bufferedReader().use { it.readText() }
        return substituteTemplates(parse(text).body)
    }

    /**
     * The wiki-librarian agent's fully-substituted prompt body (same text the Claude path injects
     * via `--agents`). Reused as the system prompt for the agentic (openai-compatible) librarian
     * path, which cannot use `--agents`. Throws if the bundled resource is missing (packaging defect).
     */
    fun librarianPromptBody(): String {
        val raw = WikiAgentsArg::class.java.getResourceAsStream(LIBRARIAN_PATH)
            ?: throw IllegalStateException("Plugin resource not found: $LIBRARIAN_PATH")
        val text = raw.bufferedReader().use { it.readText() }
        return substituteTemplates(parse(text).body)
    }

    private fun addAgentTo(root: JsonObject, resourcePath: String, modelId: String = "") {
        val raw = WikiAgentsArg::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Plugin resource not found: $resourcePath")
        val text = raw.bufferedReader().use { it.readText() }
        val parsed = parse(text)
        val body = substituteTemplates(parsed.body)
        val inner = JsonObject().apply {
            addProperty("description", parsed.description)
            addProperty("prompt", body)
            if (parsed.tools.isNotEmpty()) {
                add("tools", JsonArray().apply { parsed.tools.forEach { add(it) } })
            }
            if (modelId.isNotBlank()) {
                addProperty("model", modelId)
            }
        }
        root.add(parsed.name, inner)
    }

    private val PLACEHOLDER_RX = Regex("""\{\{([a-z0-9-]+)\}\}""")

    private fun substituteTemplates(body: String): String =
        PLACEHOLDER_RX.replace(body) { match ->
            val name = match.groupValues[1]
            try {
                PromptResource.load(name)
            } catch (_: IllegalArgumentException) {
                // Leave the placeholder untouched if the prompt resource is not found —
                // this path means a packaging defect; tests catch it.
                match.value
            }
        }

    internal data class Parsed(
        val name: String,
        val description: String,
        val tools: List<String>,
        val body: String,
    )

    internal fun parse(text: String): Parsed {
        val lines = text.lines()
        require(lines.isNotEmpty() && lines[0].trim() == "---") {
            "Agent file must start with YAML frontmatter delimiter '---'"
        }
        var endFrontmatter = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") { endFrontmatter = i; break }
        }
        require(endFrontmatter > 0) { "Agent file frontmatter has no closing '---'" }

        var name: String? = null
        var description: String? = null
        var tools = emptyList<String>()
        for (i in 1 until endFrontmatter) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "name" -> name = value
                "description" -> description = value
                "tools" -> tools = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        requireNotNull(name) { "Agent file missing 'name' in frontmatter" }
        requireNotNull(description) { "Agent file missing 'description' in frontmatter" }
        require(name!!.isNotBlank()) { "Agent 'name' is blank" }
        require(description!!.isNotBlank()) { "Agent 'description' is blank" }

        val body = lines.drop(endFrontmatter + 1).joinToString("\n").trim()
        require(body.isNotEmpty()) { "Agent file body is empty" }

        return Parsed(name = name!!, description = description!!, tools = tools, body = body)
    }
}
