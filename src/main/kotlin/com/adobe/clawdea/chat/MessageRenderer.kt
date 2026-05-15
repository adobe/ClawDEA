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
 * Generates HTML fragments for appending into the JCEF chat view.
 * All output is designed for full Chromium CSS rendering.
 */
class MessageRenderer(var autoAcceptEdits: Boolean = false, private val projectBasePath: String? = null) {

    fun renderUserMessage(text: String): String {
        val html = markdownToHtml(text)
        return """
            <div class="message">
                <div class="message-label user-label">You</div>
                <div class="user-bubble">$html</div>
            </div>
        """.trimIndent()
    }

    fun renderAssistantText(text: String): String {
        val html = markdownToHtml(text)
        return """
            <div class="message">
                <div class="message-label assistant-label">Claude</div>
                <div class="assistant-bubble">$html</div>
            </div>
        """.trimIndent()
    }

    fun renderToolUseCompact(toolName: String, input: String, toolUseId: String = ""): String {
        if (toolName == "Read" || toolName == "Edit" || toolName == "Write") {
            val path = extractJsonString(input, "file_path")
            if (path != null) return renderFileLink(path, toolUseId, toolName)
        }
        return renderToolUse(toolName, input, toolUseId)
    }

    fun renderToolUse(toolName: String, input: String, toolUseId: String = ""): String {
        val parsed = parseToolInput(toolName, input)
        val icon = when {
            toolName.contains("Bash", ignoreCase = true) -> "▶"
            toolName.contains("Read", ignoreCase = true) -> "📄"
            toolName.contains("Edit", ignoreCase = true) || toolName.contains("Write", ignoreCase = true) -> "✏"
            toolName.contains("Grep", ignoreCase = true) || toolName.contains("Glob", ignoreCase = true) -> "🔍"
            else -> "⚙"
        }
        val title = escapeHtml(parsed.title)
        val body = escapeHtml(parsed.body)
        val truncated = if (body.length > 500) body.substring(0, 500) + "..." else body
        val bodyHtml = if (truncated.isNotBlank()) {
            """<pre class="tool-input">$truncated</pre>"""
        } else ""
        val extraHtml = parsed.rawHtml
        val safeId = escapeHtml(toolUseId)
        val stopHtml = if (toolUseId.isNotEmpty()) {
            """<span class="tool-stop-btn" data-action="stop-tool">${"■"}</span>"""
        } else ""
        return """
            <div class="tool-block" data-tool-id="$safeId">
                <div class="tool-header">
                    <span class="tool-icon">$icon</span>
                    <span class="tool-name">$title</span>
                    $stopHtml
                </div>
                $bodyHtml
                $extraHtml
            </div>
        """.trimIndent()
    }

    /**
     * Render a compact edit link for the chat panel.
     * The filepath is a clickable link that opens the IDEA diff editor.
     * Status badge shows: Pending, Reviewing..., Accepted, Rejected, Modified, Auto-accepted.
     */
    private fun tooltipPath(filePath: String): String {
        val base = projectBasePath
        if (base != null) {
            val normalized = filePath.replace('\\', '/')
            val normalizedBase = base.replace('\\', '/')
            if (normalized.startsWith("$normalizedBase/")) {
                return normalized.removePrefix("$normalizedBase/")
            }
        }
        return filePath
    }

    fun renderEditLink(
        filePath: String,
        toolUseId: String,
        status: String,
        label: String = "Edit",
        showActions: Boolean = false,
    ): String {
        val fileName = escapeHtml(extractFileName(filePath))
        val fullPath = escapeHtml(tooltipPath(filePath))
        val safeId = escapeHtml(toolUseId)
        val safeStatus = escapeHtml(status)
        val safeLabel = escapeHtml(label)

        val trailingHtml = if (showActions) {
            """<span class="edit-action-accept" data-action="edit-accept" data-tool-id="$safeId">[Accept]</span>""" +
            """<span class="edit-action-reject" data-action="edit-reject" data-tool-id="$safeId">[Reject]</span>"""
        } else {
            val statusClass = when (status.lowercase()) {
                "accepted", "auto-accepted" -> "edit-status-accepted"
                "rejected" -> "edit-status-rejected"
                "modified" -> "edit-status-modified"
                "reviewing..." -> "edit-status-reviewing"
                else -> "edit-status-pending"
            }
            """<span class="$statusClass">[$safeStatus]</span>"""
        }

        return """
            <div class="edit-link" data-tool-id="$safeId">
                <span class="tool-icon">&#x270F;</span>
                <span class="edit-link-label">$safeLabel</span>
                <span class="edit-link-path" title="$fullPath" data-action="open-diff" data-tool-id="$safeId">$fileName</span>
                $trailingHtml
            </div>
        """.trimIndent()
    }

    fun renderFileLink(filePath: String, toolUseId: String, label: String = "Read"): String {
        val fileName = escapeHtml(extractFileName(filePath))
        val tooltip = escapeHtml(tooltipPath(filePath))
        val absPath = escapeHtml(filePath)
        val safeId = escapeHtml(toolUseId)
        val icon = when (label) {
            "Edit", "Write" -> "&#x270F;"
            else -> "&#x1F4C4;"
        }
        val safeLabel = escapeHtml(label)
        return """
            <div class="edit-link" data-tool-id="$safeId">
                <span class="tool-icon">$icon</span>
                <span class="edit-link-label">$safeLabel</span>
                <span class="edit-link-path" title="$tooltip" data-action="open-file" data-file-path="$absPath">$fileName</span>
            </div>
        """.trimIndent()
    }

    private fun extractFileName(path: String): String {
        val lastSlash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (lastSlash >= 0) path.substring(lastSlash + 1) else path
    }

    private data class ToolDisplay(val title: String, val body: String, val rawHtml: String = "")

    private fun parseToolInput(toolName: String, input: String): ToolDisplay {
        val lower = toolName.lowercase()
        return when {
            lower.contains("bash") -> {
                val desc = extractJsonString(input, "description")
                val cmd = extractJsonString(input, "command")
                ToolDisplay(
                    title = desc ?: "Bash",
                    body = cmd ?: input,
                )
            }
            lower.contains("read") -> {
                val path = extractJsonString(input, "file_path")
                ToolDisplay(title = "Read ${path ?: ""}", body = "")
            }
            lower.contains("edit") -> {
                val path = extractJsonString(input, "file_path")
                ToolDisplay(title = "Edit ${path ?: ""}", body = "")
            }
            lower.contains("write") -> {
                val path = extractJsonString(input, "file_path")
                ToolDisplay(title = "Write ${path ?: ""}", body = "")
            }
            lower.contains("grep") -> {
                val pattern = extractJsonString(input, "pattern")
                val path = extractJsonString(input, "path")
                val suffix = if (path != null) " in $path" else ""
                ToolDisplay(title = "Grep: ${pattern ?: ""}$suffix", body = "")
            }
            lower.contains("glob") -> {
                val pattern = extractJsonString(input, "pattern")
                ToolDisplay(title = "Glob: ${pattern ?: ""}", body = "")
            }
            lower.contains("agent") || lower.contains("task") -> {
                val desc = extractJsonString(input, "description")
                    ?: extractJsonString(input, "prompt")
                ToolDisplay(title = desc ?: toolName, body = "")
            }
            else -> {
                val titleField = extractJsonString(input, "description")
                    ?: extractJsonString(input, "query")
                    ?: extractJsonString(input, "prompt")
                    ?: extractJsonString(input, "name")
                    ?: extractJsonString(input, "url")
                    ?: extractJsonString(input, "skill")
                    ?: extractJsonString(input, "file_path")
                    ?: extractJsonString(input, "path")
                val title = if (titleField != null) "$toolName: $titleField" else toolName
                val body = formatJsonAsParams(input, titleField)
                ToolDisplay(title = title, body = body)
            }
        }
    }

    companion object {
        private val KNOWN_EXTENSIONS = setOf(
            "kt", "java", "xml", "json", "html", "css", "js", "ts", "tsx", "jsx",
            "yaml", "yml", "properties", "gradle", "kts", "md", "txt", "sh", "py",
        )

        private val FILE_WITH_LINE = Regex("""^(.+\.(\w+))(?::(\d+)(?:-\d+|(?::(\d+)))?)?$""")
        private val CLASS_METHOD = Regex("""^[A-Z]\w+\.\w+(?:\(.*\))?$""")
        private val PASCAL_CLASS = Regex("""^[A-Z][a-z]\w*(?:[A-Z][a-z]\w*)+$""")

        internal fun isCodeReference(escaped: String): Boolean {
            val text = escaped
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
            if (text.contains(' ') || text.length < 3) return false
            if (text.startsWith("/")) return true
            if (text.contains("/") && FILE_WITH_LINE.matches(text.substringAfterLast("/"))) return true
            val fileMatch = FILE_WITH_LINE.matchEntire(text)
            if (fileMatch != null && fileMatch.groupValues[2].lowercase() in KNOWN_EXTENSIONS) return true
            if (CLASS_METHOD.matches(text)) return true
            if (PASCAL_CLASS.matches(text)) return true
            return false
        }

        private val PROSE_CODE_REF = Regex(
            """(?<!["\w/])(/[\w./-]+\.(?:""" +
                KNOWN_EXTENSIONS.joinToString("|") +
                """)(?::\d+(?:-\d+|(?::\d+))?)?)""" +
                """|(?<![.\w])([A-Z]\w+\.\w+\(\))""",
        )

        /** Extract a string value from a JSON object by key. Minimal parser, no dependencies. */
        internal fun extractJsonString(json: String, key: String): String? {
        val searchKey = "\"$key\""
        val keyIndex = json.indexOf(searchKey)
        if (keyIndex == -1) return null
        val colonIndex = json.indexOf(':', keyIndex + searchKey.length)
        if (colonIndex == -1) return null
        val afterColon = json.substring(colonIndex + 1).trimStart()
        if (afterColon.isEmpty() || afterColon[0] != '"') return null
        val sb = StringBuilder()
        var i = 1
        while (i < afterColon.length) {
            val c = afterColon[i]
            if (c == '\\' && i + 1 < afterColon.length) {
                when (afterColon[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '/' -> sb.append('/')
                    else -> { sb.append('\\'); sb.append(afterColon[i + 1]) }
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        val result = sb.toString()
        return if (result.isNotBlank()) result else null
        }
    }

    fun renderToolResult(content: String): String {
        if (content.isBlank()) return ""
        val escaped = escapeHtml(content)
        val truncated = if (escaped.length > 500) escaped.substring(0, 500) + "..." else escaped
        val chevron = "▸"
        return """
            <div class="tool-result-header" data-action="toggle-tool-body">
                <span class="tool-chevron">$chevron</span>
                <span class="tool-result-label">Output</span>
            </div>
            <div class="tool-body-collapsible"><pre class="tool-output">$truncated</pre></div>
        """.trimIndent()
    }

    fun renderInfoMessage(text: String): String {
        val escaped = escapeHtml(text)
        return """<div class="info-block">$escaped</div>"""
    }

    fun renderError(message: String): String {
        val escaped = escapeHtml(message)
        return """<div class="error-block">$escaped</div>"""
    }

    fun renderCostInfo(costUsd: Double, totalElapsedMs: Long = 0): String {
        val parts = mutableListOf<String>()
        if (totalElapsedMs > 0) {
            parts.add(formatElapsed(totalElapsedMs))
        }
        if (costUsd > 0) {
            parts.add("${'$'}${String.format("%.4f", costUsd)}")
        }
        val inner = parts.joinToString("<span style='margin: 0 4px; color: #45475a;'>|</span>")
        return """<div class="cost-info">$inner</div>"""
    }

    fun renderCollapsedToolBlock(toolName: String, toolUseId: String): String {
        val safeId = escapeHtml(toolUseId)
        val safeName = escapeHtml(toolName)
        return """<div class="tool-block-collapsed" data-tool-id="$safeId">
        <span class="tool-icon">&#9881;</span>
        <span class="tool-name">$safeName</span>
    </div>"""
    }

    fun renderSkillBadge(skillName: String): String {
        val safe = escapeHtml(skillName)
        return """<span class="skill-badge">$safe</span>"""
    }

    fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    internal fun formatElapsed(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> String.format("%.1fs", ms / 1000.0)
            else -> {
                val min = ms / 60_000
                val sec = (ms % 60_000) / 1000
                "${min}m ${sec}s"
            }
        }
    }

    /** Extract key-value pairs from JSON and format as readable lines, excluding the value already used in the title. */
    private fun formatJsonAsParams(json: String, excludeValue: String?): String {
        val params = mutableListOf<String>()
        val pattern = Regex(""""(\w+)"\s*:\s*(?:"([^"\\]*(?:\\.[^"\\]*)*)"|(\d+(?:\.\d+)?)|(\btrue\b|\bfalse\b|\bnull\b))""")
        for (match in pattern.findAll(json)) {
            val key = match.groupValues[1]
            val value = when {
                match.groupValues[2].isNotEmpty() -> match.groupValues[2]
                match.groupValues[3].isNotEmpty() -> match.groupValues[3]
                match.groupValues[4].isNotEmpty() -> match.groupValues[4]
                else -> continue
            }
            if (value == excludeValue) continue
            params.add("$key: $value")
        }
        return params.joinToString("\n")
    }

    internal fun processRefLinks(html: String): String {
        val openTag = "{[ref:"
        val closeTag = "]}"
        val sb = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val start = html.indexOf(openTag, i)
            if (start == -1) {
                sb.append(html, i, html.length)
                break
            }
            sb.append(html, i, start)
            val queryStart = start + openTag.length
            val pipeIdx = html.indexOf('|', queryStart)
            if (pipeIdx == -1) {
                sb.append(html, start, start + openTag.length)
                i = start + openTag.length
                continue
            }
            val closeIdx = html.indexOf(closeTag, pipeIdx + 1)
            if (closeIdx == -1) {
                sb.append(html, start, start + openTag.length)
                i = start + openTag.length
                continue
            }
            val rawQuery = html.substring(queryStart, pipeIdx)
            val label = html.substring(pipeIdx + 1, closeIdx)
                .replace("(", "&#40;").replace(")", "&#41;")
            val query = rawQuery
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
            sb.append("""<code class="inline-code code-ref" data-action="navigate" data-ref="${escapeHtml(query)}">$label</code>""")
            i = closeIdx + closeTag.length
        }
        return sb.toString()
    }

    private fun markdownToHtml(markdown: String): String {
        var html = escapeHtml(markdown)

        // Code blocks with language tag
        html = html.replace(Regex("```(\\w+)?\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)) {
            val lang = it.groupValues[1]
            val code = it.groupValues[2]
            val langTag = if (lang.isNotBlank()) """<span class="code-lang">$lang</span>""" else ""
            """<div class="code-block">$langTag<pre><code>$code</code></pre></div>"""
        }

        // Explicit code reference links: {[ref:query|label]}
        html = processRefLinks(html)

        // Inline code — all backtick content is clickable; recognized patterns
        // resolve via IDEA indexes, unrecognized falls back to Search Everywhere
        html = html.replace(Regex("`([^`]+)`")) {
            val content = it.groupValues[1]
            val ref = content.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            """<code class="inline-code code-ref" data-action="navigate" data-ref="${escapeHtml(ref)}">$content</code>"""
        }

        // Outside-backtick code references: absolute paths and Class.method()
        html = PROSE_CODE_REF.replace(html) { m ->
            val ref = m.value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            """<span class="code-ref" data-action="navigate" data-ref="${escapeHtml(ref)}">${m.value}</span>"""
        }

        // Headers
        html = html.replace(Regex("^### (.+)$", RegexOption.MULTILINE)) { "<h3>${it.groupValues[1]}</h3>" }
        html = html.replace(Regex("^## (.+)$", RegexOption.MULTILINE)) { "<h2>${it.groupValues[1]}</h2>" }
        html = html.replace(Regex("^# (.+)$", RegexOption.MULTILINE)) { "<h1>${it.groupValues[1]}</h1>" }

        // Bold and italic
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
        html = html.replace(Regex("\\*(.+?)\\*")) { "<em>${it.groupValues[1]}</em>" }

        // Tables: header row, separator row, body rows
        html = html.replace(Regex("""^(\|.+\|)\n(\|[\s\-:|]+\|)\n((?:\|.+\|(?:\n|$))+)""", RegexOption.MULTILINE)) {
            buildHtmlTable(it.groupValues[1], it.groupValues[3])
        }

        // Bullet lists: group consecutive items so newlines between them are consumed
        html = html.replace(Regex("""(^[\-\*] .+(\n[\-\*] .+)*)""", RegexOption.MULTILINE)) {
            it.value.lines().joinToString("") { line ->
                val content = line.replaceFirst(Regex("^[\\-\\*] "), "")
                """<div class="list-item">${content}</div>"""
            }
        }

        // Line breaks
        html = html.replace("\n", "<br>")

        return html
    }

    private fun buildHtmlTable(headerLine: String, bodyBlock: String): String {
        fun parseCells(line: String): List<String> =
            line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

        val headers = parseCells(headerLine)
        val headerHtml = headers.joinToString("") { "<th>$it</th>" }

        val rows = bodyBlock.trim().lines().filter { it.contains("|") }
        val rowsHtml = rows.joinToString("") { row ->
            val cells = parseCells(row)
            "<tr>${cells.joinToString("") { "<td>$it</td>" }}</tr>"
        }

        return """<table class="md-table"><thead><tr>$headerHtml</tr></thead><tbody>$rowsHtml</tbody></table>"""
    }
}
