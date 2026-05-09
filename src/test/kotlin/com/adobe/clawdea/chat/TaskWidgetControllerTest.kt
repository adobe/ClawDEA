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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TaskWidgetControllerTest {

    private lateinit var controller: TaskWidgetController

    @Before
    fun setUp() {
        controller = TaskWidgetController()
    }

    @Test
    fun `onTaskEvent adds task on TaskCreated`() {
        controller.onTaskEvent(CliEvent.TaskEvent.TaskCreated(
            id = "1", subject = "Write tests", description = "Write all the tests", activeForm = "Writing tests",
        ))

        val html = controller.renderWidget()
        assertTrue(html.contains("Write tests"))
        assertTrue(html.contains("task-item"))
    }

    @Test
    fun `onTaskEvent updates task status on TaskStatusChanged`() {
        controller.onTaskEvent(CliEvent.TaskEvent.TaskCreated(
            id = "1", subject = "Write tests", description = "", activeForm = "Writing tests",
        ))
        controller.onTaskEvent(CliEvent.TaskEvent.TaskStatusChanged(id = "1", newStatus = "in_progress"))

        val html = controller.renderWidget()
        assertTrue(html.contains("in-progress"))
        assertTrue(html.contains("Writing tests"))
    }

    @Test
    fun `onTaskEvent marks task completed`() {
        controller.onTaskEvent(CliEvent.TaskEvent.TaskCreated(
            id = "1", subject = "Write tests", description = "", activeForm = null,
        ))
        controller.onTaskEvent(CliEvent.TaskEvent.TaskStatusChanged(id = "1", newStatus = "completed"))

        val html = controller.renderWidget()
        assertTrue(html.contains("completed"))
    }

    @Test
    fun `onTaskEvent removes task on TaskDeleted`() {
        controller.onTaskEvent(CliEvent.TaskEvent.TaskCreated(
            id = "1", subject = "Write tests", description = "", activeForm = null,
        ))
        controller.onTaskEvent(CliEvent.TaskEvent.TaskDeleted(id = "1"))

        val html = controller.renderWidget()
        assertFalse(html.contains("Write tests"))
    }

    @Test
    fun `renderWidget returns empty string when no tasks`() {
        assertEquals("", controller.renderWidget())
    }

    @Test
    fun `renderWidget shows active task with spinner`() {
        controller.onTaskEvent(CliEvent.TaskEvent.TaskCreated(
            id = "1", subject = "Explore context", description = "", activeForm = "Exploring context",
        ))
        controller.onTaskEvent(CliEvent.TaskEvent.TaskStatusChanged(id = "1", newStatus = "in_progress"))

        val html = controller.renderWidget()
        assertTrue(html.contains("spinner"))
        assertTrue(html.contains("Exploring context"))
    }

    @Test
    fun `renderWidget shows multiple tasks with correct statuses`() {
        controller.onTaskEvent(CliEvent.TaskEvent.TaskCreated(
            id = "1", subject = "Task A", description = "", activeForm = null,
        ))
        controller.onTaskEvent(CliEvent.TaskEvent.TaskCreated(
            id = "2", subject = "Task B", description = "", activeForm = null,
        ))
        controller.onTaskEvent(CliEvent.TaskEvent.TaskStatusChanged(id = "1", newStatus = "completed"))
        controller.onTaskEvent(CliEvent.TaskEvent.TaskStatusChanged(id = "2", newStatus = "in_progress"))

        val html = controller.renderWidget()
        assertTrue(html.contains("Task A"))
        assertTrue(html.contains("Task B"))
        assertTrue(html.contains("completed"))
        assertTrue(html.contains("in-progress"))
    }

    @Test
    fun `reset clears all tasks`() {
        controller.onTaskEvent(CliEvent.TaskEvent.TaskCreated(
            id = "1", subject = "Task A", description = "", activeForm = null,
        ))
        controller.reset()
        assertEquals("", controller.renderWidget())
    }

    @Test
    fun `hasTasks returns true when tasks exist`() {
        assertFalse(controller.hasTasks())
        controller.onTaskEvent(CliEvent.TaskEvent.TaskCreated(
            id = "1", subject = "Task A", description = "", activeForm = null,
        ))
        assertTrue(controller.hasTasks())
    }
}
