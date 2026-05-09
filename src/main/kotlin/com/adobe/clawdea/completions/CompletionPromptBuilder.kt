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
// src/main/kotlin/com/adobe/clawdea/completions/CompletionPromptBuilder.kt
package com.adobe.clawdea.completions

/**
 * Builds a fill-in-the-middle prompt from the document text, caret offset,
 * and assembled context. Returns a system prompt + user message pair
 * suitable for the Anthropic Messages API.
 */
class CompletionPromptBuilder {

    data class CompletionPrompt(
        val systemPrompt: String,
        val userMessage: String,
    )

    /**
     * Build a completion prompt.
     *
     * @param documentText Full text of the current document
     * @param caretOffset  Byte offset of the cursor position
     * @param contextSnippet  Pre-assembled context from the Context Engine (may be blank)
     */
    fun build(documentText: String, caretOffset: Int, contextSnippet: String): CompletionPrompt {
        val safeOffset = caretOffset.coerceIn(0, documentText.length)
        val prefix = documentText.substring(0, safeOffset)
        val suffix = documentText.substring(safeOffset)

        val prefixLines = prefix.lines()
        val recentPrefix = if (prefixLines.size > MAX_PREFIX_LINES) {
            prefixLines.takeLast(MAX_PREFIX_LINES).joinToString("\n")
        } else {
            prefix
        }

        val suffixLines = suffix.lines()
        val truncatedSuffix = if (suffixLines.size > MAX_SUFFIX_LINES) {
            suffixLines.take(MAX_SUFFIX_LINES).joinToString("\n")
        } else {
            suffix
        }

        val currentLine = prefixLines.lastOrNull().orEmpty()
        val lineContext = detectLineContext(currentLine)

        val systemPrompt = buildString {
            append("You are a code completion engine. ")
            append("Output ONLY the code that should be inserted at the cursor position. ")
            append("Do not include any explanation, markdown formatting, or code fences. ")
            append("Do not repeat code that already exists before or after the cursor. ")
            append("Output only the new code to insert. If no completion is appropriate, output nothing.")
            if (lineContext != null) {
                append("\n\nIMPORTANT: ")
                append(lineContext)
            }
            if (contextSnippet.isNotBlank()) {
                append("\n\n<context>\n")
                append(contextSnippet)
                append("\n</context>")
            }
            if (truncatedSuffix.isNotBlank()) {
                append("\n\n<code_after_cursor>\n")
                append(truncatedSuffix)
                append("\n</code_after_cursor>")
            }
        }

        val userMessage = buildString {
            append("Complete the code at the cursor position (marked with <CURSOR>):\n\n")
            append(recentPrefix)
            append("<CURSOR>")
        }

        return CompletionPrompt(systemPrompt, userMessage)
    }

    private fun detectLineContext(currentLine: String): String? {
        val trimmed = currentLine.trimStart()
        return when {
            trimmed.startsWith("//") ->
                "The cursor is inside a line comment. Continue the comment text only. Do NOT generate code."
            trimmed.startsWith("/*") || trimmed.startsWith("*") ->
                "The cursor is inside a block comment. Continue the comment text only. Do NOT generate code."
            trimmed.startsWith("#") ->
                "The cursor is inside a comment. Continue the comment text only. Do NOT generate code."
            trimmed.startsWith("\"\"\"") || trimmed.startsWith("///") ->
                "The cursor is inside a doc comment or string. Continue the documentation text only. Do NOT generate code."
            else -> null
        }
    }

    companion object {
        private const val MAX_PREFIX_LINES = 30
        private const val MAX_SUFFIX_LINES = 10

        fun needsSemanticContext(documentText: String, caretOffset: Int): Boolean {
            val safeOffset = caretOffset.coerceIn(0, documentText.length)
            val prefix = documentText.substring(0, safeOffset)
            val currentLine = prefix.substringAfterLast('\n', prefix).trimStart()
            if (currentLine.startsWith("//") || currentLine.startsWith("/*") ||
                currentLine.startsWith("*") || currentLine.startsWith("#") ||
                currentLine.startsWith("///") || currentLine.isBlank()
            ) {
                return false
            }
            return true
        }
    }
}
