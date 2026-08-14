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
package com.adobe.clawdea.cli

import com.adobe.clawdea.util.JsonScan

class TaskEventExtractor {

    fun extract(toolUse: CliEvent.ToolUse, result: CliEvent.ToolResult): CliEvent.TaskEvent? {
        return when (toolUse.name) {
            "TaskCreate" -> extractTaskCreated(toolUse.input, result.content)
            "TaskUpdate" -> extractTaskUpdated(toolUse.input, result.content)
            else -> null
        }
    }

    private fun extractTaskCreated(input: String, resultContent: String): CliEvent.TaskEvent? {
        val subject = JsonScan.string(input, "subject") ?: return null
        val description = JsonScan.string(input, "description") ?: ""
        val activeForm = JsonScan.string(input, "activeForm")
        val id = extractTaskId(resultContent) ?: return null
        return CliEvent.TaskEvent.TaskCreated(id, subject, description, activeForm)
    }

    private fun extractTaskUpdated(input: String, resultContent: String): CliEvent.TaskEvent? {
        val taskId = JsonScan.string(input, "taskId") ?: return null
        val status = JsonScan.string(input, "status") ?: return null
        if (status == "deleted") {
            return CliEvent.TaskEvent.TaskDeleted(taskId)
        }
        return CliEvent.TaskEvent.TaskStatusChanged(taskId, status)
    }

    companion object {
        private val TASK_ID_PATTERN = Regex("""#(\d+)""")

        fun extractTaskId(resultContent: String): String? {
            return TASK_ID_PATTERN.find(resultContent)?.groupValues?.get(1)
        }
    }
}
