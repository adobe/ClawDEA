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

import org.junit.Assert.*
import org.junit.Test

class TaskEventExtractorTest {

    private val extractor = TaskEventExtractor()

    @Test
    fun `extracts TaskCreated from TaskCreate tool use and result`() {
        val toolUse = CliEvent.ToolUse(
            id = "toolu_001",
            name = "TaskCreate",
            input = """{"subject":"Write tests","description":"Write unit tests for the feature","activeForm":"Writing tests"}""",
        )
        val result = CliEvent.ToolResult(
            toolUseId = "toolu_001",
            content = "Task #5 created successfully: Write tests",
        )

        val event = extractor.extract(toolUse, result)
        assertNotNull(event)
        assertTrue(event is CliEvent.TaskEvent.TaskCreated)
        val created = event as CliEvent.TaskEvent.TaskCreated
        assertEquals("5", created.id)
        assertEquals("Write tests", created.subject)
        assertEquals("Write unit tests for the feature", created.description)
        assertEquals("Writing tests", created.activeForm)
    }

    @Test
    fun `extracts TaskStatusChanged from TaskUpdate tool use`() {
        val toolUse = CliEvent.ToolUse(
            id = "toolu_002",
            name = "TaskUpdate",
            input = """{"taskId":"5","status":"in_progress"}""",
        )
        val result = CliEvent.ToolResult(
            toolUseId = "toolu_002",
            content = "Updated task #5 status",
        )

        val event = extractor.extract(toolUse, result)
        assertNotNull(event)
        assertTrue(event is CliEvent.TaskEvent.TaskStatusChanged)
        val changed = event as CliEvent.TaskEvent.TaskStatusChanged
        assertEquals("5", changed.id)
        assertEquals("in_progress", changed.newStatus)
    }

    @Test
    fun `extracts TaskDeleted from TaskUpdate with deleted status`() {
        val toolUse = CliEvent.ToolUse(
            id = "toolu_003",
            name = "TaskUpdate",
            input = """{"taskId":"3","status":"deleted"}""",
        )
        val result = CliEvent.ToolResult(
            toolUseId = "toolu_003",
            content = "Updated task #3 status",
        )

        val event = extractor.extract(toolUse, result)
        assertNotNull(event)
        assertTrue(event is CliEvent.TaskEvent.TaskDeleted)
        assertEquals("3", (event as CliEvent.TaskEvent.TaskDeleted).id)
    }

    @Test
    fun `returns null for non-task tool use`() {
        val toolUse = CliEvent.ToolUse(
            id = "toolu_004",
            name = "Read",
            input = """{"file_path":"/tmp/test.kt"}""",
        )
        val result = CliEvent.ToolResult(
            toolUseId = "toolu_004",
            content = "file contents",
        )

        assertNull(extractor.extract(toolUse, result))
    }

    @Test
    fun `extracts task id from result message`() {
        assertEquals("5", TaskEventExtractor.extractTaskId("Task #5 created successfully: Write tests"))
        assertEquals("12", TaskEventExtractor.extractTaskId("Updated task #12 status"))
        assertNull(TaskEventExtractor.extractTaskId("some other message"))
    }

    @Test
    fun `handles TaskCreate without activeForm`() {
        val toolUse = CliEvent.ToolUse(
            id = "toolu_005",
            name = "TaskCreate",
            input = """{"subject":"Simple task","description":"A task"}""",
        )
        val result = CliEvent.ToolResult(
            toolUseId = "toolu_005",
            content = "Task #1 created successfully: Simple task",
        )

        val event = extractor.extract(toolUse, result)
        assertNotNull(event)
        val created = event as CliEvent.TaskEvent.TaskCreated
        assertNull(created.activeForm)
    }
}
