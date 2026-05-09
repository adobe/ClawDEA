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

data class SkillFrontmatter(val name: String, val description: String)

class SkillFrontmatterParser {

    fun parse(content: String): SkillFrontmatter? {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null

        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex < 0) return null

        val frontmatterLines = lines.subList(1, endIndex + 1)
        val fields = mutableMapOf<String, String>()

        for (line in frontmatterLines) {
            val colonIndex = line.indexOf(':')
            if (colonIndex < 0) continue
            val key = line.substring(0, colonIndex).trim()
            val rawValue = line.substring(colonIndex + 1).trim()
            // Strip surrounding quotes if present
            val value = if (rawValue.startsWith("\"") && rawValue.endsWith("\"")) {
                rawValue.substring(1, rawValue.length - 1)
            } else {
                rawValue
            }
            fields[key] = value
        }

        val name = fields["name"] ?: return null
        val description = fields["description"] ?: ""
        return SkillFrontmatter(name, description)
    }
}
