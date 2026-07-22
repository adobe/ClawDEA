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
package com.adobe.clawdea.mcp

import com.adobe.clawdea.chat.editreview.EditDiffReviewer
import com.adobe.clawdea.chat.editreview.EditOutcome
import com.adobe.clawdea.chat.editreview.EditReviewOutcomes
import com.intellij.openapi.project.Project
import java.io.File

/**
 * MCP tools for interactive edit review.
 * propose_edit and propose_write open a diff dialog and block
 * until the user clicks Accept or Reject.
 * When auto-accept is enabled in settings, edits are applied immediately
 * without showing a diff dialog.
 */
class McpEditReviewTools(private val project: Project) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(McpEditReviewTools::class.java)
    private val reviewer = EditDiffReviewer(project)

    fun registerAll(router: McpToolRouter) {
        router.register(
            name = "propose_edit",
            description = "Propose a text edit to a file. Opens a diff review dialog for the user. Blocks until the user accepts or rejects. Use this instead of the built-in Edit tool when edit review is enabled.",
            properties = listOf(
                Triple("file_path", "string", "Absolute path to the file to edit"),
                Triple("old_string", "string", "The existing text to replace (must match exactly)"),
                Triple("new_string", "string", "The replacement text"),
            ),
            required = listOf("file_path", "old_string", "new_string"),
            handler = ::handleProposeEdit,
        )
        router.register(
            name = "propose_write",
            description = "Propose writing/overwriting a file. Opens a diff review dialog for the user. Blocks until the user accepts or rejects. Use this instead of the built-in Write tool when edit review is enabled.",
            properties = listOf(
                Triple("file_path", "string", "Absolute path to the file to write"),
                Triple("content", "string", "The full file content to write"),
            ),
            required = listOf("file_path", "content"),
            handler = ::handleProposeWrite,
        )
        router.register(
            name = "propose_multi_edit",
            description = "Propose multiple sequential edits to a single file. Takes file_path and edits as a JSON-encoded array of {old_string, new_string} objects. Opens a single diff review dialog and blocks until the user accepts or rejects. Use this instead of the built-in MultiEdit tool when edit review is enabled.",
            properties = listOf(
                Triple("file_path", "string", "Absolute path to the file to edit"),
                Triple("edits", "string", "JSON-encoded array of {old_string, new_string} objects"),
            ),
            required = listOf("file_path", "edits"),
            handler = ::handleProposeMultiEdit,
        )
        router.register(
            name = "propose_notebook_edit",
            description = "Propose an edit to a Jupyter notebook cell. Opens a diff review dialog for the full notebook JSON and blocks until the user accepts or rejects. Use this instead of the built-in NotebookEdit tool when edit review is enabled.",
            properties = listOf(
                Triple("notebook_path", "string", "Absolute path to the .ipynb file"),
                Triple("cell_id", "string", "Id of the cell to target"),
                Triple("new_source", "string", "New source for the cell (ignored for edit_mode='delete')"),
                Triple("cell_type", "string", "'code' | 'markdown' | 'raw'. Only used for edit_mode='insert'"),
                Triple("edit_mode", "string", "'replace' | 'insert' | 'delete'. Default 'replace'"),
            ),
            required = listOf("notebook_path", "cell_id", "new_source"),
            handler = ::handleProposeNotebookEdit,
        )
    }

    private fun handleProposeEdit(args: Map<String, String>): McpToolRouter.ToolResult {
        val filePath = args["file_path"]
            ?: return McpToolRouter.ToolResult("Missing 'file_path' argument", isError = true)
        val oldString = args["old_string"]
            ?: return McpToolRouter.ToolResult("Missing 'old_string' argument", isError = true)
        val newString = args["new_string"]
            ?: return McpToolRouter.ToolResult("Missing 'new_string' argument", isError = true)

        val file = File(filePath)
        val originalContent = if (file.exists()) file.readText() else ""

        if (!originalContent.contains(oldString)) {
            return McpToolRouter.ToolResult(
                "old_string not found in $filePath. The file may have changed since you read it.",
                isError = true,
            )
        }

        val proposedContent = buildProposedEdit(originalContent, oldString, newString)
        return reviewAndRespond(filePath, originalContent, proposedContent)
    }

    private fun handleProposeWrite(args: Map<String, String>): McpToolRouter.ToolResult {
        val filePath = args["file_path"]
            ?: return McpToolRouter.ToolResult("Missing 'file_path' argument", isError = true)
        val content = args["content"]
            ?: return McpToolRouter.ToolResult("Missing 'content' argument", isError = true)

        val file = File(filePath)
        val originalContent = if (file.exists()) file.readText() else ""

        return reviewAndRespond(filePath, originalContent, content)
    }

    private fun handleProposeMultiEdit(args: Map<String, String>): McpToolRouter.ToolResult {
        val filePath = args["file_path"]
            ?: return McpToolRouter.ToolResult("Missing 'file_path' argument", isError = true)
        val editsJson = args["edits"]
            ?: return McpToolRouter.ToolResult("Missing 'edits' argument", isError = true)

        val edits = parseEditsJson(editsJson)
            ?: return McpToolRouter.ToolResult("Invalid 'edits' JSON: expected array of {old_string, new_string}", isError = true)

        val file = File(filePath)
        val originalContent = if (file.exists()) file.readText() else ""

        val result = applyMultiEdit(originalContent, edits)
        return when (result) {
            is MultiEditResult.Failure -> McpToolRouter.ToolResult(result.message, isError = true)
            is MultiEditResult.Success -> reviewAndRespond(filePath, originalContent, result.content)
        }
    }

    private fun handleProposeNotebookEdit(args: Map<String, String>): McpToolRouter.ToolResult {
        val path = args["notebook_path"]
            ?: return McpToolRouter.ToolResult("Missing 'notebook_path' argument", isError = true)
        val cellId = args["cell_id"]
            ?: return McpToolRouter.ToolResult("Missing 'cell_id' argument", isError = true)
        val newSource = args["new_source"]
            ?: return McpToolRouter.ToolResult("Missing 'new_source' argument", isError = true)
        val cellType = args["cell_type"]?.takeIf { it.isNotBlank() }
        val mode = args["edit_mode"]?.takeIf { it.isNotBlank() } ?: "replace"

        val file = File(path)
        if (!file.exists()) {
            return McpToolRouter.ToolResult("Notebook not found: $path", isError = true)
        }
        val originalJson = file.readText()

        val result = applyNotebookEdit(originalJson, cellId, newSource, cellType, mode)
        return when (result) {
            is NotebookEditResult.Failure -> McpToolRouter.ToolResult(result.message, isError = true)
            is NotebookEditResult.Success -> reviewAndRespond(path, originalJson, result.content)
        }
    }

    /**
     * The user accepted (or auto-accept was on) but [EditDiffReviewer.applyContent] reported the
     * write did not land. Surface a truthful error so the model does not believe the file exists.
     * See the observed relative-path failure: a "docs/…" path resolved off-project, mkdirs failed,
     * yet the old code still returned "ACCEPTED".
     */
    private fun writeFailed(filePath: String): McpToolRouter.ToolResult {
        log.warn("reviewAndRespond: write reported as not applied for $filePath")
        EditReviewOutcomes.put(filePath, "REJECTED")
        return McpToolRouter.ToolResult(
            "Failed to write $filePath: the file could not be created on disk. " +
                "Pass an absolute path inside the project and verify the directory is writable.",
            isError = true,
        )
    }

    private fun reviewAndRespond(
        filePath: String,
        originalContent: String,
        proposedContent: String,
    ): McpToolRouter.ToolResult {
        // Auto-accept ONLY when the dedicated edit-review toggle is on. The
        // toolApprovalMode setting governs tool-call permissions (whether
        // ClawDEA asks the user for tool authorization), which is orthogonal
        // to whether file edits should be reviewed before applying. Conflating
        // the two caused users with toolApprovalMode="allow-all" to have all
        // edits auto-applied even with autoAcceptEdits=false.
        if (McpServer.getInstance(project).activeAutoAcceptEdits) {
            log.info("reviewAndRespond: auto-accepting edit for $filePath")
            if (!reviewer.applyContent(filePath, proposedContent)) {
                return writeFailed(filePath)
            }
            EditReviewOutcomes.put(filePath, "AUTO-ACCEPTED")
            return McpToolRouter.ToolResult(formatResult("ACCEPTED", filePath, null))
        }

        log.info("reviewAndRespond: opening diff for $filePath (thread=${Thread.currentThread().name})")
        val result = reviewer.review(filePath, originalContent, proposedContent)
        log.info("reviewAndRespond: review completed for $filePath with outcome=${result.outcome}")

        val outcome = when (result.outcome) {
            EditOutcome.ACCEPTED -> {
                if (!reviewer.applyContent(filePath, proposedContent)) return writeFailed(filePath)
                "ACCEPTED"
            }
            EditOutcome.REJECTED -> "REJECTED"
            EditOutcome.MODIFIED -> {
                if (result.modifiedContent != null) {
                    if (!reviewer.applyContent(filePath, result.modifiedContent)) return writeFailed(filePath)
                }
                "MODIFIED"
            }
            EditOutcome.DISMISSED -> "REJECTED"
        }
        // Store outcome so ChatPanel can read it (CLI ToolResult content is empty for MCP tools)
        EditReviewOutcomes.put(filePath, outcome)
        return McpToolRouter.ToolResult(formatResult(outcome, filePath, result.modifiedContent))
    }

    sealed class MultiEditResult {
        data class Success(val content: String) : MultiEditResult()
        data class Failure(val index: Int, val message: String) : MultiEditResult()
    }

    sealed class NotebookEditResult {
        data class Success(val content: String) : NotebookEditResult()
        data class Failure(val message: String) : NotebookEditResult()
    }

    companion object {
        fun buildProposedEdit(original: String, oldString: String, newString: String): String {
            return if (original.contains(oldString)) {
                original.replaceFirst(oldString, newString)
            } else {
                original
            }
        }

        fun formatResult(outcome: String, filePath: String, modifiedContent: String?): String {
            return when (outcome) {
                "ACCEPTED" -> "ACCEPTED: edit applied to $filePath"
                "REJECTED" -> "REJECTED: user declined this edit to $filePath. Please reconsider your approach."
                "MODIFIED" -> {
                    val contentSummary = if (modifiedContent != null) {
                        "\n\nUser's version:\n$modifiedContent"
                    } else ""
                    "MODIFIED: user applied a different version to $filePath$contentSummary"
                }
                else -> "$outcome: $filePath"
            }
        }

        fun applyMultiEdit(original: String, edits: List<Pair<String, String>>): MultiEditResult {
            if (edits.isEmpty()) return MultiEditResult.Failure(-1, "edits array is empty")
            var working = original
            edits.forEachIndexed { index, (oldString, newString) ->
                if (!working.contains(oldString)) {
                    return MultiEditResult.Failure(index, "edit #$index: old_string not found: ${oldString.take(80)}")
                }
                working = working.replaceFirst(oldString, newString)
            }
            return MultiEditResult.Success(working)
        }

        fun parseEditsJson(json: String): List<Pair<String, String>>? {
            return try {
                val element = com.google.gson.JsonParser.parseString(json)
                if (!element.isJsonArray) return null
                val result = mutableListOf<Pair<String, String>>()
                for (item in element.asJsonArray) {
                    if (!item.isJsonObject) return null
                    val obj = item.asJsonObject
                    val oldStr = obj.get("old_string")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
                    val newStr = obj.get("new_string")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
                    result.add(oldStr to newStr)
                }
                result
            } catch (_: Exception) { null }
        }

        fun applyNotebookEdit(
            originalJson: String,
            cellId: String,
            newSource: String,
            cellType: String?,
            editMode: String,
        ): NotebookEditResult {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
            val root = try {
                com.google.gson.JsonParser.parseString(originalJson)
            } catch (_: Exception) {
                return NotebookEditResult.Failure("invalid notebook JSON")
            }
            if (!root.isJsonObject) return NotebookEditResult.Failure("notebook root is not a JSON object")
            val rootObj = root.asJsonObject
            val cells = rootObj.get("cells")
            if (cells == null || !cells.isJsonArray) return NotebookEditResult.Failure("notebook has no 'cells' array")
            val cellsArr = cells.asJsonArray

            fun availableIds(): List<String> = cellsArr
                .mapNotNull { el -> el.takeIf { it.isJsonObject }?.asJsonObject?.get("id")?.takeIf { it.isJsonPrimitive }?.asString }
                .take(10)

            fun newSourceArray(): com.google.gson.JsonArray {
                val arr = com.google.gson.JsonArray()
                arr.add(newSource)
                return arr
            }

            when (editMode) {
                "replace" -> {
                    for (el in cellsArr) {
                        if (!el.isJsonObject) continue
                        val obj = el.asJsonObject
                        if (obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString == cellId) {
                            obj.add("source", newSourceArray())
                            return NotebookEditResult.Success(gson.toJson(rootObj))
                        }
                    }
                    return NotebookEditResult.Failure("cell_id '$cellId' not found. Available: ${availableIds().joinToString(", ")}")
                }
                "insert" -> {
                    var anchorIndex = -1
                    cellsArr.forEachIndexed { i, el ->
                        if (el.isJsonObject && el.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString == cellId) {
                            anchorIndex = i
                        }
                    }
                    if (anchorIndex < 0) {
                        return NotebookEditResult.Failure("anchor cell_id '$cellId' not found. Available: ${availableIds().joinToString(", ")}")
                    }
                    val newCell = com.google.gson.JsonObject()
                    newCell.addProperty("cell_type", cellType ?: "code")
                    newCell.addProperty("id", java.util.UUID.randomUUID().toString())
                    newCell.add("source", newSourceArray())
                    newCell.add("metadata", com.google.gson.JsonObject())
                    if ((cellType ?: "code") == "code") {
                        newCell.add("outputs", com.google.gson.JsonArray())
                        newCell.addProperty("execution_count", null as String?)
                    }
                    val rebuilt = com.google.gson.JsonArray()
                    cellsArr.forEachIndexed { i, el ->
                        rebuilt.add(el)
                        if (i == anchorIndex) rebuilt.add(newCell)
                    }
                    rootObj.add("cells", rebuilt)
                    return NotebookEditResult.Success(gson.toJson(rootObj))
                }
                "delete" -> {
                    val rebuilt = com.google.gson.JsonArray()
                    var removed = false
                    for (el in cellsArr) {
                        if (el.isJsonObject && el.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString == cellId) {
                            removed = true
                            continue
                        }
                        rebuilt.add(el)
                    }
                    if (!removed) {
                        return NotebookEditResult.Failure("cell_id '$cellId' not found. Available: ${availableIds().joinToString(", ")}")
                    }
                    rootObj.add("cells", rebuilt)
                    return NotebookEditResult.Success(gson.toJson(rootObj))
                }
                else -> return NotebookEditResult.Failure("invalid edit_mode: '$editMode' (expected replace|insert|delete)")
            }
        }
    }
}
