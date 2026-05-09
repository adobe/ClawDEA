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

import com.adobe.clawdea.cli.CliEvent

class TaskWidgetController {

    private data class TaskState(
        val id: String,
        val subject: String,
        val activeForm: String?,
        var status: String = "pending",
    )

    private val tasks = LinkedHashMap<String, TaskState>()

    fun onTaskEvent(event: CliEvent.TaskEvent) {
        when (event) {
            is CliEvent.TaskEvent.TaskCreated -> {
                tasks[event.id] = TaskState(
                    id = event.id,
                    subject = event.subject,
                    activeForm = event.activeForm,
                )
            }
            is CliEvent.TaskEvent.TaskStatusChanged -> {
                tasks[event.id]?.status = event.newStatus
            }
            is CliEvent.TaskEvent.TaskDeleted -> {
                tasks.remove(event.id)
            }
        }
    }

    fun renderWidget(): String {
        if (tasks.isEmpty()) return ""

        val activeTask = tasks.values.find { it.status == "in_progress" }
        val activeHtml = if (activeTask != null) {
            val label = escapeHtml(activeTask.activeForm ?: activeTask.subject)
            """<div class="task-active">
                <span class="spinner">&#10038;</span>
                <span class="task-label">$label...</span>
            </div>"""
        } else ""

        val itemsHtml = tasks.values.joinToString("") { task ->
            val statusClass = when (task.status) {
                "completed" -> "completed"
                "in_progress" -> "in-progress"
                else -> "pending"
            }
            val icon = when (task.status) {
                "completed" -> "&#10004;"
                "in_progress" -> "&#9724;"
                else -> "&#9723;"
            }
            val subject = escapeHtml(task.subject)
            """<div class="task-item $statusClass">$icon $subject</div>"""
        }

        return """<div id="task-widget" class="task-widget">
            $activeHtml
            <div class="task-list collapsed" onclick="this.classList.toggle('collapsed')">
                $itemsHtml
            </div>
        </div>"""
    }

    fun hasTasks(): Boolean = tasks.isNotEmpty()

    fun reset() {
        tasks.clear()
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
