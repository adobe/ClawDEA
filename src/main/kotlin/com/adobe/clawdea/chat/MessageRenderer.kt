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
 * Display context for [MessageRenderer.renderToolUseEvent]. See that
 * method's docstring for how each variant changes the output.
 */
sealed class ToolMode {
    /** Tool is still in flight; in-flight UI affordances render. */
    data class Live(val autoAcceptEdits: Boolean) : ToolMode()
    /** Tool already settled when this conversation was originally streamed. */
    data class Replay(val resultContent: String?, val isError: Boolean) : ToolMode()
}

/**
 * Generates HTML fragments for appending into the JCEF chat view.
 * All output is designed for full Chromium CSS rendering.
 */
class MessageRenderer(
    var autoAcceptEdits: Boolean = false,
    private val projectBasePath: String? = null,
    private val wikiDirResolver: (() -> java.nio.file.Path?)? = null,
) {

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

    /**
     * Single source of truth for how a tool_use becomes an HTML fragment in
     * the chat. Both the live event stream
     * ([com.adobe.clawdea.chat.EventStreamHandler]) and `/resume` replay
     * ([com.adobe.clawdea.chat.SessionManager]) route through this method
     * so the two paths can never drift apart.
     *
     * Differences between live and replay are encoded in [mode]:
     *  - [ToolMode.Live] knows the auto-accept setting, so edit links get
     *    the right in-flight status ("Reviewing..." / "Auto-accepted" /
     *    "Applied"+actions). Stop buttons render. Task-widget tools emit
     *    no HTML — the [com.adobe.clawdea.chat.TaskWidgetController]
     *    displays them out of band.
     *  - [ToolMode.Replay] is settled: edits collapse to "Accepted" /
     *    "Failed", no stop button, and the tool result (when supplied)
     *    is inlined inside the tool block exactly the way
     *    `injectToolOutput` renders it live. Task tools render as a
     *    collapsed badge so the user can still see they ran.
     */
    fun renderToolUseEvent(
        toolName: String,
        input: String,
        toolUseId: String,
        mode: ToolMode,
    ): String {
        // Suppressed everywhere — the permission flow renders an interactive
        // multi-choice card instead of a generic tool block.
        if (toolName == "AskUserQuestion") return ""

        if (toolName in TASK_TOOLS) {
            // Live: the task widget owns task-tool display; emit nothing here.
            // Replay: there's no task widget to populate, so a collapsed badge
            // is the next best thing.
            return if (mode is ToolMode.Replay) renderCollapsedToolBlock(toolName, toolUseId) else ""
        }

        val isPropose = com.adobe.clawdea.chat.editreview.EditReviewCoordinator.isProposeTool(toolName)
        val isEdit = com.adobe.clawdea.chat.editreview.EditReviewCoordinator.isEditTool(toolName)
        if (isPropose || isEdit) {
            val filePath = com.adobe.clawdea.chat.editreview.EditReviewCoordinator.extractFilePath(input)
                ?: toolName
            val label = if (toolName.contains("write", ignoreCase = true)) "Write" else "Edit"
            val (status, showActions) = when (mode) {
                is ToolMode.Live -> when {
                    mode.autoAcceptEdits -> "Auto-accepted" to false
                    isPropose -> "Reviewing..." to false
                    // Layer-2 fallback: built-in Edit/Write slipped past
                    // propose_edit, so render with inline accept/reject.
                    else -> "Applied" to true
                }
                is ToolMode.Replay -> (if (mode.isError) "Failed" else "Accepted") to false
            }
            return renderEditLink(filePath, toolUseId, status, label, showActions = showActions)
        }

        if (toolName == "Read") {
            val filePath = extractJsonString(input, "file_path")
            if (filePath != null) return renderFileLink(filePath, toolUseId)
            // Fall through to a generic tool block if Read came without file_path.
        }

        val extra = when (mode) {
            is ToolMode.Replay -> if (!mode.resultContent.isNullOrBlank()) renderToolResult(mode.resultContent) else ""
            is ToolMode.Live -> ""
        }
        // Replay has no in-flight call to stop, so drop the toolUseId from
        // the outer block — that's the cue [renderToolUse] uses to decide
        // whether to render the stop button.
        val effectiveId = when (mode) {
            is ToolMode.Live -> toolUseId
            is ToolMode.Replay -> ""
        }
        return renderToolUse(toolName, input, effectiveId, extraInnerHtml = extra)
    }

    /**
     * Header label for a sub-agent card. Leads with the per-dispatch
     * [description] (the task — distinct per sub-agent); falls back to the
     * [agentType], then to "Task" when neither is present. The agent type is
     * returned as a secondary tag only when a description was used as the
     * primary name, so the type ("general-purpose", etc.) doesn't become the
     * indistinguishable name of every card.
     */
    private fun subAgentLabels(agentType: String, description: String): Pair<String, String> {
        // The CLI's default subagent_type "general-purpose" is generic and
        // uninformative as a card label — surface it as "Task" instead.
        val displayType = if (agentType.equals("general-purpose", ignoreCase = true)) "Task" else agentType
        val name = description.ifBlank { displayType.ifBlank { "Task" } }
        val secondary = if (description.isNotBlank() && displayType.isNotBlank()) displayType else ""
        return name to secondary
    }

    /**
     * Container card for a sub-agent ([SubAgentController]) dispatch. Rendered
     * expanded + running on creation; [ChatBrowserRenderer.appendIntoSubAgent]
     * appends inner steps into `.subagent-children`, and
     * [ChatBrowserRenderer.finalizeSubAgent] collapses it to a summary on completion.
     */
    fun renderSubAgentCard(agentType: String, description: String, toolUseId: String): String {
        val safeId = escapeHtml(toolUseId)
        val (name, secondary) = subAgentLabels(agentType, description)
        val safeName = escapeHtml(name)
        val secondaryHtml = if (secondary.isNotBlank()) """<span class="subagent-desc">${escapeHtml(secondary)}</span>""" else ""
        return """
            <div class="subagent-block expanded" data-tool-id="$safeId">
                <div class="subagent-header" data-action="toggle-subagent">
                    <span class="subagent-icon">&#129302;</span>
                    <span class="subagent-type">$safeName</span>
                    $secondaryHtml
                    <span class="subagent-status" data-subagent-status>&#9203; running</span>
                </div>
                <div class="subagent-children"></div>
            </div>
        """.trimIndent()
    }

    /**
     * Compact, individually-expandable one-liner for a single inner tool call of
     * a sub-agent. The step's output is injected later by the existing
     * [ChatBrowserRenderer.injectToolOutput] (it selects by `data-tool-id`
     * globally, so nesting needs no special handling); CSS hides it until the
     * row is expanded via `toggle-subagent-step`.
     */
    fun renderInnerToolUse(toolName: String, input: String, toolUseId: String, resultContent: String? = null): String {
        val parsed = parseToolInput(toolName, input)
        val icon = when {
            toolName.contains("Bash", ignoreCase = true) -> "&#9654;"
            toolName.contains("Read", ignoreCase = true) -> "&#128196;"
            toolName.contains("Edit", ignoreCase = true) || toolName.contains("Write", ignoreCase = true) -> "&#9999;"
            toolName.contains("Grep", ignoreCase = true) || toolName.contains("Glob", ignoreCase = true) ||
                toolName.startsWith("mcp__clawdea") -> "&#128269;"
            else -> "&#9881;"
        }
        val safeId = escapeHtml(toolUseId)
        val safeName = escapeHtml(toolName)
        val titleSuffix = parsed.title.removePrefix(toolName).trim()
        val argRaw = titleSuffix.ifEmpty { parsed.body }.take(80)
        val arg = escapeHtml(argRaw)
        // On replay the result is known up front, so inline it the same way
        // injectToolOutput does live — the step row stays the toggle, the body
        // is hidden until expanded.
        val resultHtml = if (!resultContent.isNullOrBlank()) renderToolResult(resultContent) else ""
        return """
            <div class="subagent-step" data-tool-id="$safeId">
                <div class="subagent-step-row" data-action="toggle-subagent-step">
                    <span class="subagent-step-icon">$icon</span>
                    <span class="subagent-step-name">$safeName</span>
                    <span class="subagent-step-arg">$arg</span>
                </div>
                $resultHtml
            </div>
        """.trimIndent()
    }

    /** Final one-line summary that replaces the live status row when a sub-agent finishes. */
    fun renderSubAgentSummary(
        status: SubAgentController.Status,
        stepCount: Int,
        resultText: String,
    ): String {
        val (glyph, cls, word) = when (status) {
            SubAgentController.Status.DONE -> Triple("&#10003;", "subagent-summary-done", "done")
            SubAgentController.Status.ERROR -> Triple("&#10007;", "subagent-summary-error", "error")
            SubAgentController.Status.ABORTED -> Triple("&#9632;", "subagent-summary-aborted", "aborted")
            SubAgentController.Status.RUNNING -> Triple("&#9203;", "subagent-summary-done", "done")
        }
        // Step counts are only known for the live stream; the persisted session
        // jsonl carries no sub-agent inner events, so a replayed card has
        // stepCount 0. Omit the count entirely in that case rather than show a
        // misleading "0 steps".
        val meta = if (stepCount >= 1) {
            val steps = if (stepCount == 1) "1 step" else "$stepCount steps"
            "$steps &middot; $word"
        } else {
            word
        }
        val firstLine = escapeHtml(resultText.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "")
        return """
            <div class="subagent-summary $cls">
                <span class="subagent-summary-glyph">$glyph</span>
                <span class="subagent-summary-meta">$meta</span>
                <span class="subagent-summary-text">$firstLine</span>
            </div>
        """.trimIndent()
    }

    /**
     * Reconstruct a finished sub-agent card for session replay: collapsed,
     * header + summary + the already-rendered inner steps. Mirrors the DOM the
     * live path ends with after finalizeSubAgent (status span removed, summary
     * inserted after header, `expanded` class dropped).
     */
    fun renderSubAgentCardFromHistory(
        agentType: String,
        description: String,
        toolUseId: String,
        status: SubAgentController.Status,
        stepCount: Int,
        resultText: String,
        childrenHtml: String,
    ): String {
        val safeId = escapeHtml(toolUseId)
        val (name, secondary) = subAgentLabels(agentType, description)
        val safeName = escapeHtml(name)
        val secondaryHtml = if (secondary.isNotBlank()) """<span class="subagent-desc">${escapeHtml(secondary)}</span>""" else ""
        val summary = renderSubAgentSummary(status, stepCount, resultText)
        return """
            <div class="subagent-block" data-tool-id="$safeId">
                <div class="subagent-header" data-action="toggle-subagent">
                    <span class="subagent-icon">&#129302;</span>
                    <span class="subagent-type">$safeName</span>
                    $secondaryHtml
                </div>
                $summary
                <div class="subagent-children">$childrenHtml</div>
            </div>
        """.trimIndent()
    }

    /**
     * Back-compat replay shortcut used by [com.adobe.clawdea.chat.SessionManager].
     * Delegates to [renderToolUseEvent] with [ToolMode.Replay].
     */
    fun renderToolUseFromHistory(
        toolName: String,
        input: String,
        toolUseId: String,
        resultContent: String? = null,
        isError: Boolean = false,
    ): String = renderToolUseEvent(
        toolName = toolName,
        input = input,
        toolUseId = toolUseId,
        mode = ToolMode.Replay(resultContent, isError),
    )

    fun renderToolUse(
        toolName: String,
        input: String,
        toolUseId: String = "",
        extraInnerHtml: String = "",
    ): String {
        val parsed = parseToolInput(toolName, input)
        val icon = when {
            isWikiReadTool(toolName) -> "📚"
            isWikiWriteTool(toolName) -> "📝"
            isEditOrWriteUnderWikiDir(toolName, input) -> "📝"
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
                $extraInnerHtml
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
            val glyph = when (status.lowercase()) {
                "auto-accepted" -> "&#9889;" // ⚡, matches Auto-allowed
                "accepted" -> "&#10003;"     // ✓
                "rejected" -> "&#10007;"     // ✗
                "modified" -> "&#9998;"      // ✎
                "reviewing..." -> "&#8230;"  // …
                else -> "&#8226;"            // •
            }
            """<span class="$statusClass">$glyph $safeStatus</span>"""
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
        val underWiki = isPathUnderWikiDir(filePath)
        val icon = when {
            underWiki && (label == "Edit" || label == "Write") -> "&#x1F4DD;"
            underWiki -> "&#x1F4DA;"
            label == "Edit" || label == "Write" -> "&#x270F;"
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

    private fun isWikiReadTool(toolName: String): Boolean {
        val lower = toolName.lowercase()
        // Match suffix to handle MCP-namespaced forms like
        // "mcp__clawdea-intellij__read_wiki_page".
        val readNames = listOf("read_wiki_page", "search_wiki", "read_sibling_wiki")
        return readNames.any { lower.endsWith(it) }
    }

    private fun isWikiWriteTool(toolName: String): Boolean {
        val lower = toolName.lowercase()
        val writeNames = listOf("record_wiki_suggestion")
        return writeNames.any { lower.endsWith(it) }
    }

    private fun isEditOrWriteUnderWikiDir(toolName: String, input: String): Boolean {
        val lower = toolName.lowercase()
        val isEditOrWriteName =
            lower == "edit" ||
            lower == "write" ||
            lower == "propose_edit" ||
            lower == "propose_write" ||
            lower.endsWith("__edit") ||
            lower.endsWith("__write") ||
            lower.endsWith("__propose_edit") ||
            lower.endsWith("__propose_write")
        if (!isEditOrWriteName) return false
        val filePath = extractJsonString(input, "file_path") ?: return false
        return isPathUnderWikiDir(filePath)
    }

    private fun isPathUnderWikiDir(filePath: String): Boolean {
        val resolver = wikiDirResolver ?: return false
        val wikiDir = resolver() ?: return false
        val wikiDirNormalized = wikiDir.toAbsolutePath().normalize()

        val resolved = try {
            val raw = java.nio.file.Path.of(filePath)
            if (raw.isAbsolute) {
                raw.normalize()
            } else {
                val base = projectBasePath?.let { java.nio.file.Path.of(it) }
                    ?: return false
                base.resolve(raw).toAbsolutePath().normalize()
            }
        } catch (_: java.nio.file.InvalidPathException) {
            return false
        } catch (_: SecurityException) {
            return false
        }

        return resolved.startsWith(wikiDirNormalized)
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
            lower.endsWith("read_sibling_wiki") -> {
                val repo = extractJsonString(input, "repo")
                val page = extractJsonString(input, "page")
                val suffix = listOfNotNull(repo, page).joinToString("/")
                ToolDisplay(title = "Read sibling Wiki${if (suffix.isNotEmpty()) " $suffix" else ""}", body = "")
            }
            lower.endsWith("read_wiki_page") -> {
                val name = extractJsonString(input, "name")
                ToolDisplay(title = "Read Wiki${if (!name.isNullOrEmpty()) " $name" else ""}", body = "")
            }
            lower.endsWith("search_wiki") -> {
                val query = extractJsonString(input, "query")
                ToolDisplay(title = "Search Wiki${if (!query.isNullOrEmpty()) ": $query" else ""}", body = "")
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
        // Task-widget tools — rendered as a single collapsed badge in the
        // live stream because their full output drives the [TaskWidgetController]
        // sidebar instead of the main chat. Keep the list in sync with the
        // matching branch in `EventStreamHandler.handleEvent`.
        internal val TASK_TOOLS = setOf("TaskCreate", "TaskUpdate", "TodoWrite", "TodoRead")

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

    fun renderToolResult(content: String, autoAllowed: Boolean = false): String {
        if (content.isBlank()) return ""
        val escaped = escapeHtml(content)
        val truncated = if (escaped.length > 500) escaped.substring(0, 500) + "..." else escaped
        val chevron = "▸"
        val autoBadge = if (autoAllowed) {
            """ <span class="tool-auto-allowed" title="Auto-allowed by Tool approval = Allow all">&#9889; Auto-allowed</span>"""
        } else ""
        return """
            <div class="tool-result-header" data-action="toggle-tool-body">
                <span class="tool-chevron">$chevron</span>
                <span class="tool-result-label">Output</span>$autoBadge
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

    /** Sticky banner shown while a `/goal` is active. */
    fun renderGoalBanner(state: GoalController.GoalState): String {
        val cond = escapeHtml(state.condition.ifBlank { "(active goal)" })
        val turns = when {
            state.turnCount <= 0 -> "starting&#8230;"   // before the first evaluation
            state.turnCount == 1 -> "1 turn"
            else -> "${state.turnCount} turns"
        }
        val reasonHtml = if (state.latestReason.isNotBlank()) {
            """<span class="goal-banner-reason">${escapeHtml(state.latestReason)}</span>"""
        } else ""
        return """
            <div id="goal-banner" class="goal-banner">
                <span class="goal-banner-icon">&#9678;</span>
                <span class="goal-banner-cond">$cond</span>
                <span class="goal-banner-meta">$turns</span>
                $reasonHtml
                <span class="goal-banner-clear" data-action="run-slash-command" data-slash="/goal clear">Clear</span>
            </div>
        """.trimIndent()
    }

    /** Inline, muted note rendered for each goal evaluation (matches CC's transcript reason). */
    fun renderGoalProgress(reason: String): String {
        val safe = escapeHtml(reason)
        return """<div class="goal-progress">&#8635; goal: $safe</div>"""
    }

    /** Inline note rendered when a goal's condition is met. */
    fun renderGoalAchieved(state: GoalController.GoalState): String {
        val cond = escapeHtml(state.condition.ifBlank { "goal" })
        return """<div class="goal-achieved">&#10003; Goal achieved: $cond</div>"""
    }

    fun renderCostInfo(
        model: String?,
        effort: String?,
        costUsd: Double,
        totalElapsedMs: Long = 0,
    ): String {
        val parts = mutableListOf<String>()
        if (!model.isNullOrBlank()) {
            parts.add(escapeHtml(prettyModelName(model)))
        }
        if (!effort.isNullOrBlank()) {
            parts.add("effort ${escapeHtml(effort)}")
        }
        if (totalElapsedMs > 0) {
            parts.add(formatElapsed(totalElapsedMs))
        }
        if (costUsd > 0) {
            parts.add("${'$'}${String.format("%.4f", costUsd)}")
        }
        val inner = parts.joinToString("<span style='margin: 0 4px; color: #45475a;'>|</span>")
        return """<div class="cost-info">$inner</div>"""
    }

    /** "claude-fable-5" -> "Fable 5"; best-effort prettifier for display. */
    internal fun prettyModelName(id: String): String =
        id.removePrefix("claude-")
            .split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

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
