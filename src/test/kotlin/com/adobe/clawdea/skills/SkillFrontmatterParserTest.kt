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
package com.adobe.clawdea.skills

import org.junit.Assert.*
import org.junit.Test

class SkillFrontmatterParserTest {

    private val parser = SkillFrontmatterParser()

    @Test
    fun `parses name and description from frontmatter`() {
        val content = """
            ---
            name: brainstorming
            description: "You MUST use this before any creative work"
            ---

            # Brainstorming Ideas Into Designs
        """.trimIndent()

        val result = parser.parse(content)
        assertNotNull(result)
        assertEquals("brainstorming", result!!.name)
        assertEquals("You MUST use this before any creative work", result.description)
    }

    @Test
    fun `parses description without quotes`() {
        val content = """
            ---
            name: systematic-debugging
            description: Use when encountering any bug, test failure, or unexpected behavior
            ---
        """.trimIndent()

        val result = parser.parse(content)
        assertNotNull(result)
        assertEquals("systematic-debugging", result!!.name)
        assertEquals("Use when encountering any bug, test failure, or unexpected behavior", result.description)
    }

    @Test
    fun `returns null for content without frontmatter`() {
        val content = "# Just a markdown file\n\nNo frontmatter here."
        assertNull(parser.parse(content))
    }

    @Test
    fun `returns null for frontmatter without name`() {
        val content = """
            ---
            description: Something
            ---
        """.trimIndent()

        assertNull(parser.parse(content))
    }

    @Test
    fun `handles extra frontmatter fields gracefully`() {
        val content = """
            ---
            name: claude-automation-recommender
            description: Analyze a codebase and recommend automations
            tools: Read, Glob, Grep, Bash
            ---
        """.trimIndent()

        val result = parser.parse(content)
        assertNotNull(result)
        assertEquals("claude-automation-recommender", result!!.name)
        assertEquals("Analyze a codebase and recommend automations", result.description)
    }

    @Test
    fun `handles empty frontmatter block`() {
        val content = """
            ---
            ---
        """.trimIndent()

        assertNull(parser.parse(content))
    }
}
