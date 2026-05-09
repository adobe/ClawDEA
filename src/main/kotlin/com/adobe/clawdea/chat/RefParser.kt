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
package com.adobe.clawdea.chat

/**
 * Parsed code reference. Lines and column are 0-based; the originating
 * syntax in `{[ref:...]}` and prose is 1-based.
 *
 * Accepted forms:
 *   path
 *   path:line
 *   path:line:col
 *   path:startLine-endLine   (range — caller should select the span)
 */
internal data class ParsedRef(
    val path: String,
    val startLine: Int?,
    val endLine: Int?,
    val column: Int,
) {
    val isRange: Boolean get() = startLine != null && endLine != null
}

internal object RefParser {
    private val PATTERN = Regex("""^(.+?)(?::(\d+)(?:(?:-(\d+))|(?::(\d+)))?)?$""")

    fun parse(ref: String): ParsedRef? {
        val cleaned = ref.replace(Regex("""\(.*\)"""), "")
        val match = PATTERN.matchEntire(cleaned) ?: return null
        val path = match.groupValues[1]
        val startLine = match.groupValues[2].toIntOrNull()?.let { it - 1 }
        val endLine = match.groupValues[3].toIntOrNull()?.let { it - 1 }
        val column = match.groupValues[4].toIntOrNull()?.let { it - 1 } ?: 0
        return ParsedRef(path, startLine, endLine, column)
    }
}
