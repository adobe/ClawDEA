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
package com.adobe.clawdea.completions

object CompletionSanitizer {

    private val FENCE_LINE = Regex("""^```\w*\s*$""")

    fun sanitize(raw: String, currentLinePrefix: String): String {
        var text = stripMarkdownFences(raw)
        text = stripDuplicateCommentPrefix(text, currentLinePrefix)
        return text.trimEnd()
    }

    private fun stripMarkdownFences(text: String): String {
        val lines = text.lines().toMutableList()
        if (lines.isNotEmpty() && FENCE_LINE.matches(lines.first().trim())) {
            lines.removeFirst()
        }
        if (lines.isNotEmpty() && FENCE_LINE.matches(lines.last().trim())) {
            lines.removeLast()
        }
        return lines.joinToString("\n")
    }

    private fun stripDuplicateCommentPrefix(text: String, currentLinePrefix: String): String {
        val trimmedPrefix = currentLinePrefix.trimStart()
        val commentPrefix = when {
            trimmedPrefix.startsWith("///") -> "///"
            trimmedPrefix.startsWith("//") -> "//"
            trimmedPrefix.startsWith("/*") -> "/*"
            trimmedPrefix.startsWith("*") -> "*"
            trimmedPrefix.startsWith("#") -> "#"
            else -> return text
        }

        val firstLine = text.lines().firstOrNull() ?: return text
        val trimmedFirst = firstLine.trimStart()
        if (trimmedFirst.startsWith(commentPrefix)) {
            val stripped = trimmedFirst.removePrefix(commentPrefix).trimStart()
            val remaining = text.lines().drop(1)
            return (listOf(stripped) + remaining).joinToString("\n")
        }
        return text
    }
}
